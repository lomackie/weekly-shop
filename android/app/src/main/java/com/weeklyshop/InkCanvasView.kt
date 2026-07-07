package com.weeklyshop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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

    private val strokes = mutableListOf<MutableList<Point>>()

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

    private var touchHelper: TouchHelper? = null
    private var rawDrawingWanted = false

    /** True once the SDK has delivered any raw callback: the pen pipeline works. */
    private var rawCallbackSeen = false

    /** Set when the pen produced MotionEvents but never raw callbacks: the SDK
     *  is present but inert (wrong firmware, etc.) — stop trusting it. */
    private var rawAbandoned = false

    val isEmpty: Boolean get() = strokes.isEmpty()

    var onStrokesChanged: (() -> Unit)? = null

    // ---------------------------------------------------------------- BOOX

    private val rawInputCallback = object : RawInputCallback() {
        private var pending: MutableList<Point>? = null

        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint) {
            Log.d(TAG, "onBeginRawDrawing at ${point.x},${point.y}")
            rawCallbackSeen = true
            pending = mutableListOf()
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) {
            pending?.add(Point(point.x, point.y, point.timestamp))
        }

        override fun onEndRawDrawing(shortcut: Boolean, point: TouchPoint) {}

        override fun onRawDrawingTouchPointListReceived(points: TouchPointList) {
            Log.d(TAG, "onRawDrawingTouchPointListReceived: ${points.points.size} points")
            // The full stroke arrives here at pen-up; prefer it over the
            // incremental moves, which can drop the first few samples.
            val stroke = points.points
                .map { Point(it.x, it.y, it.timestamp) }
                .toMutableList()
                .ifEmpty { pending ?: return }
            pending = null
            strokes.add(stroke)
            drawStrokeToBitmap(stroke)
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
                        .setLimitRect(Rect(0, 0, width, height), emptyList())
                        .openRawDrawing()
                }.onSuccess {
                    touchHelper = it
                    Log.d(TAG, "TouchHelper active, limit ${width}x$height")
                    runCatching { it.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL) }
                    setRawDrawing(true)
                }.onFailure {
                    Log.i(TAG, "Pen SDK unavailable, using fallback rendering: $it")
                }
            }
        } else if (helper != null) {
            runCatching { helper.setLimitRect(Rect(0, 0, w, h), emptyList()) }
        }
    }

    override fun onDetachedFromWindow() {
        runCatching { touchHelper?.closeRawDrawing() }
        touchHelper = null
        super.onDetachedFromWindow()
    }

    /** Call from Activity.onResume/onPause: raw drawing must not stay on in
     *  the background or the pen keeps inking over other apps. */
    fun setActive(active: Boolean) {
        setRawDrawing(active)
        if (active) invalidate()
    }

    private fun setRawDrawing(enabled: Boolean) {
        rawDrawingWanted = enabled && !rawAbandoned
        runCatching { touchHelper?.setRawDrawingEnabled(rawDrawingWanted) }
    }

    /** Repaint the view from the committed bitmap, temporarily dropping the
     *  raw layer so stale SDK ink is cleared from the screen. */
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
        // here as well would record every stroke twice. But if the raw
        // pipeline has never delivered a callback, it isn't actually reading
        // the pen — abandon it and draw through the normal path.
        if (rawDrawingWanted && touchHelper != null) {
            if (rawCallbackSeen) return true
            Log.w(TAG, "Raw pen layer inert; falling back to standard rendering")
            rawAbandoned = true
            setRawDrawing(false)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokes.add(mutableListOf(Point(event.x, event.y, event.eventTime)))
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = strokes.lastOrNull() ?: return false
                // Historical points carry the high-frequency samples between frames.
                for (i in 0 until event.historySize) {
                    appendPoint(
                        stroke,
                        Point(
                            event.getHistoricalX(i),
                            event.getHistoricalY(i),
                            event.getHistoricalEventTime(i),
                        ),
                    )
                }
                appendPoint(stroke, Point(event.x, event.y, event.eventTime))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onStrokesChanged?.invoke()
            else -> return false
        }
        invalidate()
        return true
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
            stroke.any { p ->
                val dx = p.x - x
                val dy = p.y - y
                dx * dx + dy * dy <= r2
            }
        }
        if (removed) {
            rebuildBitmap()
            onStrokesChanged?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    fun undo() {
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.size - 1)
        rebuildBitmap()
        refreshFromBitmap()
        onStrokesChanged?.invoke()
    }

    fun clear() {
        strokes.clear()
        rebuildBitmap()
        refreshFromBitmap()
        onStrokesChanged?.invoke()
    }

    private fun rebuildBitmap() {
        bitmapCanvas?.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        redrawAllStrokes()
    }

    private fun redrawAllStrokes() {
        for (stroke in strokes) drawStrokeToBitmap(stroke)
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
    fun strokesAsJson(): JSONArray {
        val startTime = strokes.firstOrNull()?.firstOrNull()?.t ?: 0L
        return JSONArray(strokes.map { stroke ->
            JSONArray(stroke.map { p ->
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

        private val IS_BOOX = android.os.Build.MANUFACTURER.equals("ONYX", ignoreCase = true)
    }
}
