package com.fmc.walkie

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class PatternView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onComplete: ((List<Int>) -> Unit)? = null

    private val dots = Array(9) { Dot(it) }
    private val chosen = ArrayList<Int>()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 10f
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#546E7A") }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CAF50") }
    private var touchX = 0f
    private var touchY = 0f
    private var tracking = false

    private inner class Dot(val index: Int) {
        var x = 0f
        var y = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = minOf(w, h).toFloat()
        val margin = size * 0.16f
        val step = (size - margin * 2) / 2
        val offsetX = (w - size) / 2 + margin
        val offsetY = (h - size) / 2 + margin
        for (i in 0 until 9) {
            dots[i].x = offsetX + (i % 3) * step
            dots[i].y = offsetY + (i / 3) * step
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                chosen.clear()
                tracking = true
                hit(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                touchX = event.x
                touchY = event.y
                hit(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                if (chosen.isNotEmpty()) onComplete?.invoke(ArrayList(chosen))
                chosen.clear()
                invalidate()
            }
            else -> return false
        }
        return true
    }

    private fun hit(x: Float, y: Float) {
        val radius = width * 0.07f
        for (dot in dots) {
            if (!chosen.contains(dot.index) && hypot(x - dot.x, y - dot.y) < radius + 20f) {
                chosen.add(dot.index)
                touchX = dot.x
                touchY = dot.y
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (i in 1 until chosen.size) {
            val a = dots[chosen[i - 1]]
            val b = dots[chosen[i]]
            canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
        }
        if (tracking && chosen.isNotEmpty()) {
            val last = dots[chosen.last()]
            canvas.drawLine(last.x, last.y, touchX, touchY, linePaint)
        }
        val radius = width * 0.035f
        for (dot in dots) {
            canvas.drawCircle(dot.x, dot.y, radius, if (chosen.contains(dot.index)) activePaint else dotPaint)
        }
    }
}
