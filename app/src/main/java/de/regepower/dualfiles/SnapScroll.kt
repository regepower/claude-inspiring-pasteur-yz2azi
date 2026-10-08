package de.regepower.dualfiles

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Horizontal pager without AndroidX: pages of equal width that always snap into place. */
class SnapScroll(context: Context) : HorizontalScrollView(context) {
    var pageWidth = 1
    var pageCount = 1
    var onPage: ((Int) -> Unit)? = null

    private var flung = false

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    fun snapTo(page: Int) {
        smoothScrollTo(page.coerceIn(0, pageCount - 1) * pageWidth, 0)
    }

    override fun fling(velocityX: Int) {
        flung = true
        val pos = scrollX / pageWidth.toFloat()
        val target = when {
            abs(velocityX) < 300 -> pos.roundToInt()
            velocityX > 0 -> ceil(pos).toInt()
            else -> floor(pos).toInt()
        }
        snapTo(target)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) flung = false
        val handled = super.onTouchEvent(ev)
        val end = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
        if (end && !flung) snapTo((scrollX / pageWidth.toFloat()).roundToInt())
        return handled
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onPage?.invoke((l / pageWidth.toFloat()).roundToInt().coerceIn(0, pageCount - 1))
    }
}
