package de.regepower.dualfiles

import android.content.Context
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ListView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * ListView with a "hold" gesture: after a long press on a row the list stops scrolling and reports
 * the finger's sideways movement; releasing reports whether the finger moved.
 */
class HoldList(context: Context) : ListView(context) {
    var canHold: (Int) -> Boolean = { true }
    var onHoldMove: (Int, Float) -> Unit = { _, _ -> }
    var onHoldEnd: (Int, Boolean) -> Unit = { _, _ -> }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var pos = INVALID_POSITION
    private var armed = false
    private var moved = false
    private val arm = Runnable { startHold() }

    private fun startHold() {
        armed = true
        moved = false
        // Cancel the press for the list itself so it neither scrolls nor clicks.
        val c = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0)
        super.dispatchTouchEvent(c)
        c.recycle()
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun finish(wasMoved: Boolean) {
        removeCallbacks(arm)
        if (armed) {
            armed = false
            onHoldEnd(pos, wasMoved)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(arm)
                armed = false
                downX = ev.x
                downY = ev.y
                downTime = ev.downTime
                pos = pointToPosition(ev.x.toInt(), ev.y.toInt())
                if (pos != INVALID_POSITION && canHold(pos)) {
                    postDelayed(arm, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!armed) {
                    if (hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(arm)
                } else {
                    val dx = ev.x - downX
                    if (abs(dx) > slop) moved = true
                    if (moved) onHoldMove(pos, dx)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val wasArmed = armed
                finish(moved)
                if (wasArmed) return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasArmed = armed
                finish(true)
                if (wasArmed) return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
