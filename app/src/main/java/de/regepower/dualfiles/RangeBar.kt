package de.regepower.dualfiles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Horizontal scroll bar under a list. The thumb's length is the visible share of the content,
 * so a long range shows a short thumb. Dragging it, or tapping the track, moves the names.
 */
internal class RangeBar(context: Context) : View(context) {
    var onDrag: ((Int) -> Unit)? = null

    private var content = 0
    private var viewport = 0
    private var offset = 0
    private var dragging = false
    private var startX = 0f
    private var startOffset = 0
    private val minThumb = 40 * context.resources.displayMetrics.density
    private val rect = RectF()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x30, 0x80, 0x80, 0x80) }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0xA0, 0x60, 0x60, 0x60) }

    fun update(content: Int, viewport: Int, offset: Int) {
        this.content = content
        this.viewport = viewport
        this.offset = offset
        invalidate()
    }

    private fun maxOffset() = max(0, content - viewport)

    private fun thumbWidth(): Float =
        if (content <= viewport || content <= 0) width.toFloat()
        else max(minThumb, width * viewport.toFloat() / content)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val tw = thumbWidth()
        val maxOff = maxOffset()
        val x = if (maxOff == 0) 0f else (w - tw) * offset / maxOff
        rect.set(x, h * 0.25f, x + tw, h * 0.75f)
        canvas.drawRoundRect(rect, r, r, thumb)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val tw = thumbWidth()
                val x = (width - tw) * offset / max(1, maxOffset())
                dragging = true
                if (ev.x < x || ev.x > x + tw) {
                    // Tap on the track: jump with the thumb centred on the finger
                    val space = width - tw
                    if (space > 0) onDrag?.invoke(((ev.x - tw / 2) / space * maxOffset()).toInt())
                }
                startX = ev.x
                startOffset = offset
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val space = width - thumbWidth()
                if (space > 0) onDrag?.invoke((startOffset + (ev.x - startX) * maxOffset() / space).toInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
