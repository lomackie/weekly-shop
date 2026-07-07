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
import android.os.SystemClock
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
 * Full-screen writing surface: the stylus writes, a finger rub erases whole
 * strokes. Strokes are captured as (x, y, t) points for the server and drawn
 * locally for feedback.
 *
 * There is no submit button: the activity watches [onStrokesChanged] and
 * auto-sends after a writing pause — or as soon as a finished line is left
 * behind ([hasSettledFresh]), so earlier items don't wait for the writer to
 * stop. Each send takes per-page snapshots via [beginPendingBatches]; the
 * server splits a batch into lines (one item each) and the activity settles
 * each line with [linkPendingLine] (basketed: the ink STAYS, gains a ✓, and
 * is tied to its basket entry — the page is the list) or [failPendingLine]
 * (unparsed: highlighted), then [finishPending]. [releasePending] (network
 * error) puts the whole batch back into the next send.
 *
 * Erasing the last stroke of a linked item fires [onEntryErased] so the
 * activity can delete the basket entry; deleting from the basket panel calls
 * [removeEntryInk] for the reverse direction.
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

    private enum class State { FRESH, PENDING, FAILED, MATCHED }

    /** Identity matters (strokes live in batches and highlights) — keep this
     *  a plain class so equality stays referential. */
    private class Stroke(val points: MutableList<Point> = mutableListOf()) {
        var state = State.FRESH

        /** Uptime when committed; a line only counts as left behind once its
         *  newest stroke has had a moment to attract dots and crossbars. */
        val addedAt: Long = SystemClock.uptimeMillis()
    }

    private class Highlight(val rect: RectF, val strokes: List<Stroke>)

    private class Page {
        val strokes = mutableListOf<Stroke>()
        val highlights = mutableListOf<Highlight>()
    }

    class PendingBatch internal constructor(val id: Int, val strokes: JSONArray)

    private class PageBatch(val page: Page, val strokes: List<Stroke>)

    /** Ink that is in the basket: erase it and the entry goes too. */
    private class LinkedEntry(val entryId: Int, val page: Page, val strokes: List<Stroke>)

    private val linkedEntries = mutableListOf<LinkedEntry>()

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

    private val checkPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFF777777.toInt()
        textSize = 34f
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

    /** Fresh ink on finished lines — lines the pen has moved on from. True
     *  means a send with `settledOnly` would have something to carry, so the
     *  activity can flush completed items while the writer is still going. */
    val hasSettledFresh: Boolean
        get() = pages.withIndex().any { (i, page) ->
            settledFresh(page, i == pageIndex).isNotEmpty()
        }

    var onStrokesChanged: (() -> Unit)? = null
    var onPageChanged: (() -> Unit)? = null

    /** The last stroke of a basketed item was rubbed out: delete its entry. */
    var onEntryErased: ((Int) -> Unit)? = null

    // ------------------------------------------------------------- batches

    /** Snapshot fresh ink for sending, one batch per page so a send never
     *  mixes strokes from different pages into one image. With [settledOnly]
     *  the line still being written stays on the page for the next send. */
    fun beginPendingBatches(settledOnly: Boolean = false): List<PendingBatch> {
        val batches = mutableListOf<PendingBatch>()
        for ((i, page) in pages.withIndex()) {
            val fresh = if (settledOnly) {
                settledFresh(page, i == pageIndex)
            } else {
                page.strokes.filter { it.state == State.FRESH }
            }
            if (fresh.isEmpty()) continue
            fresh.forEach { it.state = State.PENDING }
            val id = nextBatchId++
            pendingBatches[id] = PageBatch(page, fresh)
            batches.add(PendingBatch(id, strokesAsJson(fresh)))
        }
        return batches
    }

    /** Fresh strokes that are safe to send mid-writing: everything except
     *  the line the newest stroke belongs to, and except lines written to so
     *  recently that a dot or crossbar may still be coming. Ink on a page
     *  that isn't showing can't be written to at all, so all of it counts. */
    private fun settledFresh(page: Page, isCurrent: Boolean): List<Stroke> {
        val fresh = page.strokes.filter { it.state == State.FRESH }
        if (!isCurrent || fresh.isEmpty()) return fresh
        val newest = fresh.maxBy { it.addedAt }
        val cutoff = SystemClock.uptimeMillis() - LINE_SETTLE_MS
        return clusterLines(fresh)
            .filter { line -> newest !in line && line.all { it.addedAt < cutoff } }
            .flatten()
    }

    /** A line of the batch became a basket entry: its ink stays on the page
     *  (the page IS the list), gains a ✓, and is tied to the entry so
     *  erasing it later removes the entry too. */
    fun linkPendingLine(batchId: Int, strokeIndices: List<Int>, entryId: Int?) {
        val batch = pendingBatches[batchId] ?: return
        val line = strokeIndices.mapNotNull { batch.strokes.getOrNull(it) }
        val alive = line.filter { it in batch.page.strokes }
        alive.forEach { it.state = State.MATCHED }
        if (entryId != null) {
            if (alive.isEmpty()) {
                // Erased while the request was in flight: the writer changed
                // their mind, so the just-created entry must go.
                post { onEntryErased?.invoke(entryId) }
            } else {
                linkedEntries.add(LinkedEntry(entryId, batch.page, alive))
            }
        }
    }

    /** A basket entry was deleted from the panel: take its ink off the page. */
    fun removeEntryInk(entryId: Int) {
        val link = linkedEntries.firstOrNull { it.entryId == entryId } ?: return
        linkedEntries.remove(link)
        val gone = link.strokes.toHashSet()
        link.page.strokes.removeAll { it in gone }
        pruneHighlights(link.page)
        collapseIfEmpty(link.page)
        rebuildBitmap()
        refreshFromBitmap()
    }

    /** Fire [onEntryErased] for links whose ink has been fully rubbed out. */
    private fun pruneLinkedEntries() {
        val erased = linkedEntries.filter { link ->
            link.strokes.none { it in link.page.strokes }
        }
        if (erased.isEmpty()) return
        linkedEntries.removeAll(erased)
        for (link in erased) {
            post { onEntryErased?.invoke(link.entryId) }
        }
    }

    /** The server could not parse a line of the batch: keep the ink and
     *  frame it so the writer knows to rub it out and retry. */
    fun failPendingLine(batchId: Int, strokeIndices: List<Int>) {
        val batch = pendingBatches[batchId] ?: return
        val line = strokeIndices.mapNotNull { batch.strokes.getOrNull(it) }
        // Strokes erased while the request was in flight no longer count.
        val alive = line.filter { it in batch.page.strokes }
        alive.forEach { it.state = State.FAILED }
        if (alive.isNotEmpty()) {
            val rect = RectF(boundsOf(alive))
            rect.inset(-HIGHLIGHT_PADDING, -HIGHLIGHT_PADDING)
            batch.page.highlights.add(Highlight(rect, alive))
        }
    }

    /** All of the batch's lines have been settled: repaint once for the lot.
     *  Any stroke the server's line results didn't cover rejoins the next
     *  send rather than staying pending forever. */
    fun finishPending(batchId: Int) {
        val batch = pendingBatches.remove(batchId) ?: return
        batch.strokes.forEach {
            if (it in batch.page.strokes && it.state == State.PENDING) {
                it.state = State.FRESH
            }
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

    /** Group strokes into handwritten lines by vertical position, top to
     *  bottom — the same two passes as the server's `segment_lines`: merge
     *  overlapping vertical extents, then fold in sliver clusters (i-dots,
     *  t-bars) hovering within [LINE_SLACK] of the typical line height. */
    private fun clusterLines(candidates: List<Stroke>): List<List<Stroke>> {
        class Cluster(var top: Float, var bottom: Float, val strokes: MutableList<Stroke>)

        val clusters = mutableListOf<Cluster>()
        val sorted = candidates
            .filter { it.points.isNotEmpty() }
            .sortedBy { s -> s.points.minOf { it.y } }
        for (stroke in sorted) {
            val top = stroke.points.minOf { it.y }
            val bottom = stroke.points.maxOf { it.y }
            val last = clusters.lastOrNull()
            if (last != null && top <= last.bottom) {
                last.bottom = maxOf(last.bottom, bottom)
                last.strokes.add(stroke)
            } else {
                clusters.add(Cluster(top, bottom, mutableListOf(stroke)))
            }
        }
        if (clusters.size <= 1) return clusters.map { it.strokes }

        val heights = clusters.map { it.bottom - it.top }.sorted()
        val slack = LINE_SLACK * heights[heights.size / 2]
        val merged = mutableListOf(clusters.first())
        for (cluster in clusters.drop(1)) {
            val prev = merged.last()
            if (cluster.top - prev.bottom < slack) {
                prev.bottom = maxOf(prev.bottom, cluster.bottom)
                prev.strokes.addAll(cluster.strokes)
            } else {
                merged.add(cluster)
            }
        }
        return merged.map { it.strokes }
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
            pruneLinkedEntries()
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
        // A quiet ✓ beside each item that made it into the basket.
        val page = pages[pageIndex]
        for (link in linkedEntries) {
            if (link.page !== page) continue
            val alive = link.strokes.filter { it in page.strokes }
            if (alive.isEmpty()) continue
            val b = boundsOf(alive)
            canvas.drawText("✓", b.right + 14f, b.bottom, checkPaint)
        }
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

        /** How far above the ink band an i-dot may float and still belong to
         *  the line below, as a fraction of the typical line height. Must
         *  match the server's `LINE_SLACK`. */
        private const val LINE_SLACK = 0.3f

        /** A line only counts as settled once this long has passed since its
         *  newest stroke: crossing a t just after starting the next line must
         *  not flush that half-written line as if it were finished. */
        private const val LINE_SETTLE_MS = 800L

        private val IS_BOOX = android.os.Build.MANUFACTURER.equals("ONYX", ignoreCase = true)
    }
}
