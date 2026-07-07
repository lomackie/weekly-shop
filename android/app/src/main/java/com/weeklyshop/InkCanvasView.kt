package com.weeklyshop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-screen writing surface: the stylus writes, a finger erases whole
 * strokes. Strokes are captured as (x, y, t) points for the server and drawn
 * locally for feedback.
 *
 * There is no submit button: the activity watches [onStrokesChanged] and
 * auto-sends after a writing pause. Each send takes per-page snapshots via
 * [beginPendingBatches]; the activity then settles each batch with
 * [commitPending] (matched: the ink vanishes), [failPending] (unparsed: the
 * ink stays and the region the server flagged is highlighted), or
 * [releasePending] (network error: the ink goes back into the next send).
 *
 * Ink lives on pages. [goLeft]/[goRight] flip between them; flipping right
 * past the last page creates a new one, but only when the current page has
 * ink — and a page left empty collapses away, so pages exist only where ink
 * is (plus the one being written on).
 *
 * On BOOX devices the Pen SDK's TouchHelper renders stylus ink directly on
 * the e-ink layer with near-zero latency; committed strokes are mirrored
 * into [bitmap] so they survive refreshes. If the raw pen pipeline never
 * delivers (non-BOOX device, emulator, firmware quirk) the stylus falls back
 * to the ordinary MotionEvent + onDraw path.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Point(val x: Float, val y: Float, val t: Long)

    private enum class State { FRESH, PENDING, FAILED }

    /** Identity matters (strokes live in batches and highlights) — keep this
     *  a plain class so equality stays referential. */
    private class Stroke(val points: MutableList<Point> = mutableListOf()) {
        var state = State.FRESH
    }

    private class Highlight(val rect: RectF, val strokes: List<Stroke>)

    private class Page {
        val strokes = mutableListOf<Stroke>()
        val highlights = mutableListOf<Highlight>()
    }

    class PendingBatch internal constructor(val id: Int, val strokes: JSONArray)

    private class PageBatch(val page: Page, val strokes: List<Stroke>)

    private val pages = mutableListOf(Page())
    private var pageIndex = 0
    private val strokes get() = pages[pageIndex].strokes
    private val highlights get() = pages[pageIndex].highlights

    private val pendingBatches = mutableMapOf<Int, PageBatch>()
    private var nextBatchId = 1

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    private val paint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val highlightPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFF777777.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }

    private var touchHelper: TouchHelper? = null
    private var rawDrawingWanted = false
    private var excludeRects: List<Rect> = emptyList()

    /** True once the SDK has delivered any raw callback: the pen pipeline works.
     *  Written from the SDK's reader thread, read on the UI thread. */
    @Volatile
    private var rawCallbackSeen = false

    /** Set when the pen produced MotionEvents but never raw callbacks: the SDK
     *  is present but inert (wrong firmware, etc.) — stop trusting it. */
    private var rawAbandoned = false

    /** Anything written that has not been sent (or failed) yet, on any page? */
    val hasFresh: Boolean
        get() = pages.any { page -> page.strokes.any { it.state == State.FRESH } }

    var onStrokesChanged: (() -> Unit)? = null
    var onPageChanged: (() -> Unit)? = null

    // ------------------------------------------------------------- batches

    /** Snapshot all fresh ink for sending, one batch per page so a send never
     *  mixes strokes from different pages into one image. */
    fun beginPendingBatches(): List<PendingBatch> {
        val batches = mutableListOf<PendingBatch>()
        for (page in pages) {
            val fresh = page.strokes.filter { it.state == State.FRESH }
            if (fresh.isEmpty()) continue
            fresh.forEach { it.state = State.PENDING }
            val id = nextBatchId++
            pendingBatches[id] = PageBatch(page, fresh)
            batches.add(PendingBatch(id, strokesAsJson(fresh)))
        }
        return batches
    }

    /** The batch became a basket entry: its ink disappears from its page. */
    fun commitPending(batchId: Int) {
        val batch = pendingBatches.remove(batchId) ?: return
        val gone = batch.strokes.toHashSet()
        batch.page.strokes.removeAll { it in gone }
        pruneHighlights(batch.page)
        collapseIfEmpty(batch.page)
        rebuildBitmap()
        refreshFromBitmap()
    }

    /** The server could not parse the batch: keep the ink and frame the
     *  region it flagged (falling back to the batch's own bounds) so the
     *  writer knows to rub it out and retry. */
    fun failPending(batchId: Int, region: RectF?) {
        val batch = pendingBatches.remove(batchId) ?: return
        // Strokes erased while the request was in flight no longer count.
        val alive = batch.strokes.filter { it in batch.page.strokes }
        alive.forEach { it.state = State.FAILED }
        if (alive.isNotEmpty()) {
            val rect = RectF(region ?: boundsOf(alive))
            rect.inset(-HIGHLIGHT_PADDING, -HIGHLIGHT_PADDING)
            batch.page.highlights.add(Highlight(rect, alive))
        }
        refreshFromBitmap()
    }

    /** Sending failed (offline etc.): the ink rejoins the next send. */
    fun releasePending(batchId: Int) {
        val batch = pendingBatches.remove(batchId) ?: return
        batch.strokes.forEach { if (it in batch.page.strokes) it.state = State.FRESH }
        refreshFromBitmap()
    }

    // --------------------------------------------------------------- pages

    val canGoLeft: Boolean get() = pageIndex > 0
    val canGoRight: Boolean get() = pageIndex < pages.lastIndex || strokes.isNotEmpty()
    val pageNumber: Int get() = pageIndex + 1
    val pageCount: Int get() = pages.size

    fun goLeft() {
        if (canGoLeft) flipTo(pageIndex - 1)
    }

    /** Flipping right past the last page mints a new one — but only off a
     *  page that has ink, so blank pages can't be stacked up. */
    fun goRight() {
        if (!canGoRight) return
        if (pageIndex == pages.lastIndex) pages.add(Page())
        flipTo(pageIndex + 1)
    }

    private fun flipTo(newIndex: Int) {
        var target = newIndex
        // Pages exist only where ink is: leaving an empty one drops it.
        if (strokes.isEmpty() && pages.size > 1) {
            pages.removeAt(pageIndex)
            if (target > pageIndex) target--
        }
        pageIndex = target.coerceIn(0, pages.lastIndex)
        rebuildBitmap()
        refreshFromBitmap()
        onPageChanged?.invoke()
    }

    /** A non-current page whose ink has all been committed vanishes. */
    private fun collapseIfEmpty(page: Page) {
        if (page.strokes.isNotEmpty()) return
        val idx = pages.indexOf(page)
        if (idx == -1 || idx == pageIndex || pages.size == 1) return
        pages.removeAt(idx)
        if (idx < pageIndex) pageIndex--
        onPageChanged?.invoke()
    }

    private fun boundsOf(batch: List<Stroke>): RectF {
        val rect = RectF()
        var first = true
        for (stroke in batch) for (p in stroke.points) {
            if (first) {
                rect.set(p.x, p.y, p.x, p.y)
                first = false
            } else {
                rect.union(p.x, p.y)
            }
        }
        return rect
    }

    /** Drop highlights whose ink has been fully erased. */
    private fun pruneHighlights(page: Page) {
        val live = page.strokes.toHashSet()
        page.highlights.removeAll { h -> h.strokes.none { it in live } }
    }

    // ---------------------------------------------------------------- BOOX

    private val rawInputCallback = object : RawInputCallback() {
        private var pending: MutableList<Point>? = null

        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint) {
            Log.d(TAG, "onBeginRawDrawing at ${point.x},${point.y}")
            rawCallbackSeen = true
            pending = mutableListOf()
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) {
            rawCallbackSeen = true
            pending?.add(Point(point.x, point.y, point.timestamp))
        }

        override fun onEndRawDrawing(shortcut: Boolean, point: TouchPoint) {}

        override fun onRawDrawingTouchPointListReceived(points: TouchPointList) {
            Log.d(TAG, "onRawDrawingTouchPointListReceived: ${points.points.size} points")
            // The full stroke arrives here at pen-up; prefer it over the
            // incremental moves, which can drop the first few samples.
            val stroke = Stroke(
                points.points
                    .map { Point(it.x, it.y, it.timestamp) }
                    .toMutableList()
                    .ifEmpty { pending ?: return }
            )
            pending = null
            strokes.add(stroke)
            drawStrokeToBitmap(stroke.points)
            // No invalidate(): the SDK already inked the e-ink layer, and a
            // view repaint here would wipe it mid-writing.
            post { onStrokesChanged?.invoke() }
        }

        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint) {}
        override fun onEndRawErasing(shortcut: Boolean, point: TouchPoint) {
            post { refreshFromBitmap() }
        }

        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) {
            eraseNear(point.x, point.y)
        }

        override fun onRawErasingTouchPointListReceived(points: TouchPointList) {
            for (p in points.points) eraseNear(p.x, p.y)
            post { refreshFromBitmap() }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val old = bitmap
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            bitmapCanvas = Canvas(it)
        }
        if (old != null) rebuildBitmap() else redrawAllStrokes()

        val helper = touchHelper
        if (helper == null && IS_BOOX && !rawAbandoned) {
            // TouchHelper.create succeeds on any device but only renders (and
            // behaves) correctly on BOOX firmware, so gate on the maker too.
            // Deferred: opening raw drawing mid-layout-pass leaves it inert.
            post {
                if (touchHelper != null || width == 0 || height == 0) return@post
                runCatching {
                    TouchHelper.create(this, rawInputCallback)
                        .setStrokeWidth(STROKE_WIDTH)
                        .setLimitRect(Rect(0, 0, width, height), excludeRects)
                        .openRawDrawing()
                }.onSuccess {
                    touchHelper = it
                    Log.d(TAG, "TouchHelper active, limit ${width}x$height")
                    runCatching { it.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL) }
                    setRawDrawing(true)
                    // Some firmware defaults to not rendering raw ink; be explicit.
                    runCatching { it.setRawDrawingRenderEnabled(true) }
                }.onFailure {
                    Log.i(TAG, "Pen SDK unavailable, using fallback rendering: $it")
                }
            }
        } else if (helper != null) {
            runCatching { helper.setLimitRect(Rect(0, 0, w, h), excludeRects) }
        }
    }

    override fun onDetachedFromWindow() {
        runCatching { touchHelper?.closeRawDrawing() }
        touchHelper = null
        super.onDetachedFromWindow()
    }

    /** Areas the raw pen layer must leave alone (the icon toolbar). */
    fun setExcludeRects(rects: List<Rect>) {
        excludeRects = rects
        val helper = touchHelper ?: return
        if (width > 0 && height > 0) {
            runCatching { helper.setLimitRect(Rect(0, 0, width, height), rects) }
        }
    }

    /** Call from Activity.onResume/onPause and around any overlay UI: raw
     *  drawing must not stay on while something sits above the canvas, or
     *  the pen inks straight over it and view updates never hit the screen. */
    fun setActive(active: Boolean) {
        setRawDrawing(active)
        if (active) invalidate()
    }

    private fun setRawDrawing(enabled: Boolean) {
        rawDrawingWanted = enabled && !rawAbandoned
        runCatching { touchHelper?.setRawDrawingEnabled(rawDrawingWanted) }
    }

    /** Repaint the view from the committed bitmap, temporarily dropping the
     *  raw layer so stale SDK ink is cleared from the screen. Also the only
     *  way sibling views' updates reach the e-ink panel while raw is on. */
    private fun refreshFromBitmap() {
        val helper = touchHelper
        if (helper != null && rawDrawingWanted) {
            runCatching { helper.setRawDrawingEnabled(false) }
            invalidate()
            post { runCatching { helper.setRawDrawingEnabled(true) } }
        } else {
            invalidate()
        }
    }

    /** Push pending sibling-view changes (status line) to the e-ink panel. */
    fun flushDisplay() = refreshFromBitmap()

    // ------------------------------------------- stylus draws, finger erases

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            Log.d(TAG, "onTouchEvent DOWN tool=${event.getToolType(0)} raw=$rawDrawingWanted helper=${touchHelper != null}")
        }
        return if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            handleDrawTouch(event)
        } else {
            handleEraseTouch(event)
        }
    }

    private fun handleDrawTouch(event: MotionEvent): Boolean {
        // The raw layer owns the pen while it is on; letting events through
        // here as well would record every stroke twice. But its callbacks
        // come from a background reader that can trail the first MotionEvents
        // (or, on broken firmware pairings, never start), so until it has
        // proven itself shadow the stroke here: if a whole stroke completes
        // with no raw callback the layer is inert — keep the shadow copy and
        // fall back to standard rendering for good.
        if (rawDrawingWanted && touchHelper != null) {
            if (rawCallbackSeen) shadowStroke = null else handleShadowStroke(event)
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokes.add(Stroke(mutableListOf(Point(event.x, event.y, event.eventTime))))
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = strokes.lastOrNull() ?: return false
                // Historical points carry the high-frequency samples between frames.
                for (i in 0 until event.historySize) {
                    appendPoint(
                        stroke.points,
                        Point(
                            event.getHistoricalX(i),
                            event.getHistoricalY(i),
                            event.getHistoricalEventTime(i),
                        ),
                    )
                }
                appendPoint(stroke.points, Point(event.x, event.y, event.eventTime))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onStrokesChanged?.invoke()
            else -> return false
        }
        invalidate()
        return true
    }

    /** Stroke recorded from MotionEvents while the raw layer is unproven. */
    private var shadowStroke: MutableList<Point>? = null

    private fun handleShadowStroke(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                shadowStroke = mutableListOf(Point(event.x, event.y, event.eventTime))
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = shadowStroke ?: return
                for (i in 0 until event.historySize) {
                    stroke.add(
                        Point(
                            event.getHistoricalX(i),
                            event.getHistoricalY(i),
                            event.getHistoricalEventTime(i),
                        ),
                    )
                }
                stroke.add(Point(event.x, event.y, event.eventTime))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val stroke = shadowStroke
                shadowStroke = null
                // A raw callback arriving any time during the stroke proves
                // the pipeline; the shadow copy is then a duplicate — drop it.
                if (rawCallbackSeen || stroke == null || stroke.size < 2) return
                Log.w(TAG, "Raw pen layer inert; falling back to standard rendering")
                rawAbandoned = true
                setRawDrawing(false)
                strokes.add(Stroke(stroke))
                drawStrokeToBitmap(stroke)
                invalidate()
                onStrokesChanged?.invoke()
            }
        }
    }

    private fun appendPoint(stroke: MutableList<Point>, p: Point) {
        val prev = stroke.lastOrNull()
        stroke.add(p)
        if (prev != null) {
            bitmapCanvas?.drawLine(prev.x, prev.y, p.x, p.y, paint)
        }
    }

    private fun handleEraseTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // Repaints are frozen while the raw pen layer is on; lift it
                // for the duration of the gesture so removals show live.
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    runCatching { touchHelper?.setRawDrawingEnabled(false) }
                }
                for (i in 0 until event.historySize) {
                    eraseNear(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                eraseNear(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (rawDrawingWanted) {
                    runCatching { touchHelper?.setRawDrawingEnabled(true) }
                }
            }
            else -> return false
        }
        return true
    }

    /** Remove any stroke passing within [ERASE_RADIUS] of (x, y). */
    private fun eraseNear(x: Float, y: Float) {
        val r2 = ERASE_RADIUS * ERASE_RADIUS
        val removed = strokes.removeAll { stroke ->
            stroke.points.any { p ->
                val dx = p.x - x
                val dy = p.y - y
                dx * dx + dy * dy <= r2
            }
        }
        if (removed) {
            pruneHighlights(pages[pageIndex])
            rebuildBitmap()
            onStrokesChanged?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        for (h in highlights) {
            canvas.drawRoundRect(h.rect, HIGHLIGHT_RADIUS, HIGHLIGHT_RADIUS, highlightPaint)
        }
    }

    /** Wipe the current page. In-flight batches settle harmlessly: their
     *  strokes are gone, so commit and fail find nothing to act on. */
    fun clear() {
        strokes.clear()
        highlights.clear()
        rebuildBitmap()
        refreshFromBitmap()
        onStrokesChanged?.invoke()
    }

    private fun rebuildBitmap() {
        bitmapCanvas?.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        redrawAllStrokes()
    }

    private fun redrawAllStrokes() {
        for (stroke in strokes) drawStrokeToBitmap(stroke.points)
    }

    private fun drawStrokeToBitmap(stroke: List<Point>) {
        val canvas = bitmapCanvas ?: return
        if (stroke.isEmpty()) return
        val path = Path().apply {
            moveTo(stroke[0].x, stroke[0].y)
            for (p in stroke.drop(1)) lineTo(p.x, p.y)
        }
        canvas.drawPath(path, paint)
    }

    /** Strokes in the wire format expected by POST /ink. */
    private fun strokesAsJson(batch: List<Stroke>): JSONArray {
        val startTime = batch.firstOrNull()?.points?.firstOrNull()?.t ?: 0L
        return JSONArray(batch.map { stroke ->
            JSONArray(stroke.points.map { p ->
                JSONObject()
                    .put("x", p.x)
                    .put("y", p.y)
                    .put("t", p.t - startTime)
            })
        })
    }

    companion object {
        private const val TAG = "InkCanvasView"
        private const val STROKE_WIDTH = 6f

        // Finger-sized: erasing should be forgiving, not surgical.
        private const val ERASE_RADIUS = 40f

        private const val HIGHLIGHT_PADDING = 18f
        private const val HIGHLIGHT_RADIUS = 14f

        private val IS_BOOX = android.os.Build.MANUFACTURER.equals("ONYX", ignoreCase = true)
    }
}
