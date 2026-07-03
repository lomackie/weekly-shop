package com.weeklyshop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-screen writing surface. Captures stylus/finger strokes as (x, y, t)
 * points for the server, and draws them for local feedback.
 *
 * This uses the ordinary Android render path, which feels laggy on e-ink.
 * Once the BOOX Pen SDK dependency is enabled, replace the onDraw path with
 * the SDK's TouchHelper so strokes render directly on the e-ink layer with
 * near-zero latency; the stroke capture below can stay exactly as it is.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Point(val x: Float, val y: Float, val t: Long)

    private val strokes = mutableListOf<MutableList<Point>>()
    private val paths = mutableListOf<Path>()
    private var currentPath: Path? = null

    private val paint = Paint().apply {
        isAntiAlias = true
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    val isEmpty: Boolean get() = strokes.isEmpty()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokes.add(mutableListOf(Point(event.x, event.y, event.eventTime)))
                currentPath = Path().apply { moveTo(event.x, event.y) }.also { paths.add(it) }
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = strokes.lastOrNull() ?: return false
                // Historical points carry the high-frequency samples between frames.
                for (i in 0 until event.historySize) {
                    stroke.add(
                        Point(
                            event.getHistoricalX(i),
                            event.getHistoricalY(i),
                            event.getHistoricalEventTime(i),
                        )
                    )
                    currentPath?.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                stroke.add(Point(event.x, event.y, event.eventTime))
                currentPath?.lineTo(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> currentPath = null
            else -> return false
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (path in paths) canvas.drawPath(path, paint)
    }

    fun clear() {
        strokes.clear()
        paths.clear()
        currentPath = null
        invalidate()
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
}
