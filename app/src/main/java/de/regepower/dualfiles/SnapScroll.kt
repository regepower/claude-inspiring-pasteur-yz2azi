package de.regepower.dualfiles

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Horizontal pager without AndroidX: pages of equal width that always snap into place.
 * A page swipe needs a clearly horizontal one-finger drag; it switches the page only after
 * [SWITCH_DISTANCE] of the page width or with a fast fling. Two fingers never switch pages.
 */
class SnapScroll(context: Context) : HorizontalScrollView(context) {
    var pageWidth = 1
    var pageCount = 1
    var onPage: ((Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = 1200 * context.resources.displayMetrics.density   // px per second
    private var downX = 0f
    private var downY = 0f
    private var startPage = 0
    private var swiping = false
    private var velocity: VelocityTracker? = null

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    fun snapTo(page: Int) {
        smoothScrollTo(page.coerceIn(0, pageCount - 1) * pageWidth, 0)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startPage = (scrollX / pageWidth.toFloat()).roundToInt()
                swiping = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> if (!swiping && ev.pointerCount == 1) {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                // Clearly sideways only: beyond 3x touch slop and at least twice as wide as high.
                if (dx > slop * 3 && dx > dy * 2) swiping = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> swiping = false
        }
        return swiping
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocity?.addMovement(ev)
        val handled = super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                val vt = velocity
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val dragged = scrollX - startPage * pageWidth
                val step = when {
                    abs(vx) > minFling -> if (vx < 0) 1 else -1          // finger left = next page
                    abs(dragged) > pageWidth * SWITCH_DISTANCE -> if (dragged > 0) 1 else -1
                    else -> 0
                }
                snapTo(startPage + step)
                swiping = false
            }
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            velocity?.recycle()
            velocity = null
        }
        return handled
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onPage?.invoke((l / pageWidth.toFloat()).roundToInt().coerceIn(0, pageCount - 1))
    }

    // No inertia from the framework: the snap above decides the page.
    override fun fling(velocityX: Int) = Unit

    private companion object {
        const val SWITCH_DISTANCE = 0.3f
    }
}
