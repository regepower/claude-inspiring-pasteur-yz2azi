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
    var vertical = false   // a bar at the right edge for up/down

    private fun length() = if (vertical) height else width
    private fun along(ev: MotionEvent) = if (vertical) ev.y else ev.x

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
        if (content <= viewport || content <= 0) length().toFloat()
        else max(minThumb, length() * viewport.toFloat() / content)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val len = if (vertical) h else w
        val thick = if (vertical) w else h
        val r = thick / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val tw = thumbWidth()
        val maxOff = maxOffset()
        val x = if (maxOff == 0) 0f else (len - tw) * offset / maxOff
        if (vertical) rect.set(thick * 0.25f, x, thick * 0.75f, x + tw) else rect.set(x, thick * 0.25f, x + tw, thick * 0.75f)
        canvas.drawRoundRect(rect, r, r, thumb)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val tw = thumbWidth()
                val x = (length() - tw) * offset / max(1, maxOffset())
                val at = along(ev)
                dragging = true
                if (at < x || at > x + tw) {
                    // Tap on the track: jump with the thumb centred on the finger
                    val space = length() - tw
                    if (space > 0) onDrag?.invoke(((at - tw / 2) / space * maxOffset()).toInt())
                }
                startX = at
                startOffset = offset
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val space = length() - thumbWidth()
                if (space > 0) onDrag?.invoke((startOffset + (along(ev) - startX) * maxOffset() / space).toInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
