package com.example.applock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** A simple 3x3 Android-style pattern input. Dots are numbered row-wise 1..9. */
class PatternView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val points = mutableListOf<Int>()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var activeColor = 0xFF3E91FF.toInt()
    private var idleColor = 0xFF777777.toInt()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = activeColor
    }

    /** Called when the finger is lifted. Arguments: the pattern string and whether it is long enough. */
    var onComplete: ((String, Boolean) -> Unit)? = null

    /** White dots for use on the dark lock screen. */
    fun setDarkStyle(dark: Boolean) {
        activeColor = if (dark) 0xFFFFFFFF.toInt() else 0xFF3E91FF.toInt()
        idleColor = if (dark) 0xFFB8BBC4.toInt() else 0xFF777777.toInt()
        linePaint.color = activeColor
        invalidate()
    }
    private val path = Path()
    private var tracking = false

    fun clearPattern() { points.clear(); tracking = false; invalidate() }
    fun pattern(): String = points.joinToString("-")
    fun isValidPattern(): Boolean = points.size >= 4

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = gridRect()
        path.reset()
        points.forEachIndexed { i, index ->
            val (x, y) = center(index, r)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
        for (i in 1..9) {
            val (x, y) = center(i, r)
            dotPaint.color = if (points.contains(i)) activeColor else idleColor
            dotPaint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, dp(if (points.contains(i)) 10f else 7f), dotPaint)
            if (points.contains(i)) {
                dotPaint.color = activeColor
                canvas.drawCircle(x, y, dp(17f), dotPaint.apply { alpha = 45 })
                dotPaint.alpha = 255
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearPattern(); tracking = true; addNearest(event.x, event.y); parent?.requestDisallowInterceptTouchEvent(true); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking) { addNearest(event.x, event.y); invalidate() }; return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    addNearest(event.x, event.y); tracking = false; invalidate(); performClick()
                    if (points.isNotEmpty()) onComplete?.invoke(pattern(), isValidPattern())
                }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun addNearest(x: Float, y: Float) {
        val rect = gridRect()
        var best = -1; var bestDistance = Float.MAX_VALUE
        for (i in 1..9) {
            if (points.contains(i)) continue
            val (cx, cy) = center(i, rect)
            val d = hypot(x - cx, y - cy)
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        if (best != -1 && bestDistance <= dp(34f)) {
            // Include the middle dot when a swipe crosses over two adjacent grid points.
            if (points.isNotEmpty()) {
                val prev = points.last()
                val pr = (prev - 1) / 3; val pc = (prev - 1) % 3
                val nr = (best - 1) / 3; val nc = (best - 1) % 3
                val dr = nr - pr; val dc = nc - pc
                if ((kotlin.math.abs(dr) == 2 && dc == 0) || (kotlin.math.abs(dc) == 2 && dr == 0) || (kotlin.math.abs(dr) == 2 && kotlin.math.abs(dc) == 2)) {
                    val mid = ((pr + nr) / 2) * 3 + ((pc + nc) / 2) + 1
                    if (!points.contains(mid)) points.add(mid)
                }
            }
            points.add(best); invalidate()
        }
    }

    private data class Rect(val left: Float, val top: Float, val size: Float)
    private fun gridRect(): Rect {
        val size = minOf(width.toFloat(), height.toFloat()) * 0.78f
        return Rect((width - size) / 2f, (height - size) / 2f, size)
    }
    private fun center(index: Int, r: Rect): Pair<Float, Float> {
        val row = (index - 1) / 3; val col = (index - 1) % 3
        return Pair(r.left + r.size * (col + 0.5f) / 3f, r.top + r.size * (row + 0.5f) / 3f)
    }
    private fun dp(v: Float) = v * resources.displayMetrics.density
}
