package de.regepower.dualfiles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws only the visible rows of a (possibly huge) text and scrolls it with one finger in both
 * directions. A drag is locked to the direction it starts in, and up/down needs a little more movement
 * than sideways, so reading across long lines does not jump rows. Two fingers zoom the text.
 * Hold a word to mark it; drag the round handles to extend the mark across rows.
 */
internal class TextGrid(context: Context) : View(context) {
    interface Source {
        fun count(): Int
        fun row(i: Int): String
        /** Widest row in characters (for the sideways range). */
        fun maxChars(): Int
        /** Line number shown left of row [i] ("" for a wrapped continuation). */
        fun label(i: Int): String = ""
        /** True when row [i + 1] continues row [i] (wrapped line): copied without a line break. */
        fun joinsNext(i: Int): Boolean = false
    }

    /** Characters reserved for line numbers at the left (0 = none). */
    var gutterChars = 0
        set(v) {
            field = v
            clampScroll()
            invalidate()
            onRange?.invoke()
        }

    private fun gutter(): Float = if (gutterChars > 0) gutterChars * charW + pad else 0f
    private fun left(): Float = pad + gutter()

    /** Characters that fit beside the line numbers (for wrapping). */
    fun columns(): Int = max(8, ((width - left() - pad) / max(1f, charW)).toInt())

    var source: Source? = null
    var onRange: (() -> Unit)? = null
    var onSelection: ((Boolean) -> Unit)? = null
    var onZoom: ((Float) -> Unit)? = null
    /** The number of [columns] may have changed (size, zoom, line numbers): wrapped text must be redone. */
    var onColumns: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val markPaint = Paint()
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad = 8 * density
    private var lineH = 0f
    private var charW = 0f

    // Double: a hex view of a big file is billions of pixels tall
    var scrollXf = 0.0
        private set
    var scrollYf = 0.0
        private set
    private var flingBaseX = 0.0
    private var flingBaseY = 0.0
    private val scroller = OverScroller(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    // Selection: anchor and focus as (row, column); start <= end after [ordered]
    var selecting = false
        private set
    private var aRow = 0
    private var aCol = 0
    private var fRow = 0
    private var fCol = 0
    private var dragHandle = 0            // 0 none, 1 start handle, 2 end handle, 3 extending after long press

    private enum class Axis { NONE, H, V }
    private var axis = Axis.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var scaling = false

    init {
        setTextSizeSp(13f)
        isFocusable = true
    }

    private val gutterPaint = Paint()
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }

    fun setColors(text: Int, mark: Int, handle: Int, background: Int = 0, label: Int = text) {
        gutterPaint.color = background
        labelPaint.color = label
        paint.color = text
        markPaint.color = mark
        handlePaint.color = handle
        invalidate()
    }

    val textSizeSp: Float get() = paint.textSize / context.resources.displayMetrics.scaledDensity

    fun setTextSizeSp(sp: Float) {
        paint.textSize = sp.coerceIn(MIN_SP, MAX_SP) * context.resources.displayMetrics.scaledDensity
        val fm = paint.fontMetrics
        lineH = fm.descent - fm.ascent + 2 * density
        charW = paint.measureText("0")
        clampScroll()
        invalidate()
        onRange?.invoke()
    }

    // ---- Geometry ----

    fun contentWidth(): Double = (source?.maxChars() ?: 0) * charW.toDouble() + left() + pad
    fun contentHeight(): Double = (source?.count() ?: 0) * lineH.toDouble() + pad
    fun rowsVisible(): Int = max(1, (height / lineH).toInt())
    fun firstRow(): Int = (scrollYf / lineH).toInt()

    private fun maxX() = max(0.0, contentWidth() - width)
    private fun maxY() = max(0.0, contentHeight() - height)

    private fun clampScroll() {
        scrollXf = scrollXf.coerceIn(0.0, maxX())
        scrollYf = scrollYf.coerceIn(0.0, maxY())
    }

    fun scrollToPos(x: Double, y: Double) {
        scroller.forceFinished(true)
        scrollXf = x
        scrollYf = y
        clampScroll()
        invalidate()
        onRange?.invoke()
    }

    /** Called when rows were added or the mode changed. */
    fun refresh() {
        clampScroll()
        invalidate()
        onRange?.invoke()
    }

    fun reset() {
        clearSelection()
        scrollToPos(0.0, 0.0)
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        val src = source ?: return
        val count = src.count()
        val first = firstRow()
        val last = min(count - 1, first + rowsVisible() + 1)
        val baseShift = -paint.fontMetrics.ascent + density
        val (sr, sc, er, ec) = ordered()
        for (i in first..last) {
            val y = (i * lineH.toDouble() - scrollYf).toFloat()
            val sx = scrollXf.toFloat()
            val text = src.row(i)
            if (selecting && i in sr..er) {
                val from = if (i == sr) sc.coerceAtMost(text.length) else 0
                val to = if (i == er) ec.coerceAtMost(text.length) else text.length
                val x1 = left() + paint.measureText(text, 0, from) - sx
                val x2 = left() + paint.measureText(text, 0, max(from, to)) - sx + if (i != er) charW / 2 else 0f
                canvas.drawRect(x1, y, max(x2, x1 + if (i != er) charW / 2 else 0f), y + lineH, markPaint)
            }
            canvas.drawText(text, left() - sx, y + baseShift, paint)
        }
        if (gutterChars > 0) {
            // Line numbers stay in place when the text moves sideways
            canvas.drawRect(0f, 0f, gutter(), height.toFloat(), gutterPaint)
            labelPaint.textSize = paint.textSize * 0.85f
            for (i in first..last) {
                val y = (i * lineH.toDouble() - scrollYf).toFloat()
                canvas.drawText(src.label(i), gutter() - pad / 2, y + baseShift, labelPaint)
            }
        }
        if (selecting) {
            drawHandle(canvas, sr, sc)
            drawHandle(canvas, er, ec)
        }
    }

    private fun handlePos(row: Int, col: Int): Pair<Float, Float> {
        val text = source?.row(row) ?: ""
        val x = left() + paint.measureText(text, 0, col.coerceAtMost(text.length)) - scrollXf.toFloat()
        val y = ((row + 1) * lineH.toDouble() - scrollYf).toFloat()
        return Pair(x, y)
    }

    private fun drawHandle(canvas: Canvas, row: Int, col: Int) {
        val (x, y) = handlePos(row, col)
        val r = 9 * density
        canvas.drawRect(x - density, y - lineH, x + density, y, handlePaint)
        canvas.drawCircle(x, y + r * 0.6f, r, handlePaint)
    }

    // ---- Selection ----

    private data class Range(val sr: Int, val sc: Int, val er: Int, val ec: Int)

    private fun ordered(): Range =
        if (aRow < fRow || (aRow == fRow && aCol <= fCol)) Range(aRow, aCol, fRow, fCol) else Range(fRow, fCol, aRow, aCol)

    /** Row and column under a touch point. */
    private fun hit(x: Float, y: Float): Pair<Int, Int> {
        val count = source?.count() ?: 0
        val row = ((y + scrollYf) / lineH).toInt().coerceIn(0, max(0, count - 1))
        val text = source?.row(row) ?: ""
        val adv = (x + scrollXf - left()).toFloat()
        val col = if (adv <= 0) 0 else paint.getOffsetForAdvance(text, 0, text.length, 0, text.length, false, adv)
        return Pair(row, col.coerceIn(0, text.length))
    }

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun selectWord(x: Float, y: Float) {
        val (row, col) = hit(x, y)
        val text = source?.row(row) ?: return
        var s = col.coerceAtMost(text.length)
        var e = s
        if (s < text.length && isWord(text[s])) {
            while (s > 0 && isWord(text[s - 1])) s--
            while (e < text.length && isWord(text[e])) e++
        } else if (s < text.length) {
            e = s + 1   // a single other character
        } else if (s > 0) {
            s--
        }
        aRow = row; aCol = s; fRow = row; fCol = e
        selecting = true
        onSelection?.invoke(true)
        invalidate()
    }

    fun clearSelection() {
        if (!selecting) return
        selecting = false
        dragHandle = 0
        onSelection?.invoke(false)
        invalidate()
    }

    /** Marked rows (null when there are more than [maxChars] characters). */
    fun selectedText(maxChars: Int): String? {
        val src = source ?: return ""
        val (sr, sc, er, ec) = ordered()
        val sb = StringBuilder()
        for (i in sr..er) {
            val t = src.row(i)
            val from = if (i == sr) sc.coerceAtMost(t.length) else 0
            val to = if (i == er) ec.coerceAtMost(t.length) else t.length
            sb.append(t, from, max(from, to))
            if (i != er && !src.joinsNext(i)) sb.append('\n')
            if (sb.length > maxChars) return null
        }
        return sb.toString()
    }

    fun selectAll() {
        val count = source?.count() ?: return
        if (count == 0) return
        aRow = 0; aCol = 0; fRow = count - 1; fCol = source!!.row(count - 1).length
        selecting = true
        onSelection?.invoke(true)
        invalidate()
    }

    // ---- Touch ----

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            clearSelection()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scaling) return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            selectWord(e.x, e.y)
            dragHandle = 3   // moving the finger now extends the mark
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (dragHandle != 0 || scaling) return false
            val fx = if (axis == Axis.V) 0 else (-vx).toInt()
            val fy = if (axis == Axis.H) 0 else (-vy).toInt()
            // Relative to the current position, so the int range of the scroller is enough
            flingBaseX = scrollXf
            flingBaseY = scrollYf
            fun lim(v: Double) = v.coerceIn(-1e9, 1e9).toInt()
            scroller.fling(0, 0, fx, fy, lim(-scrollXf), lim(maxX() - scrollXf), lim(-scrollYf), lim(maxY() - scrollYf))
            postInvalidateOnAnimation()
            return true
        }
    })

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Keep the row under the fingers in place while the size changes
            val focusRow = (scrollYf + detector.focusY) / lineH
            val focusCol = (scrollXf + detector.focusX - left()) / max(1f, charW)
            setTextSizeSp(textSizeSp * detector.scaleFactor)
            scrollXf = focusCol * charW + left() - detector.focusX.toDouble()
            scrollYf = focusRow * lineH - detector.focusY.toDouble()
            clampScroll()
            onRange?.invoke()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            onZoom?.invoke(textSizeSp)
            onColumns?.invoke()
        }
    })

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scale.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                scaling = false
                axis = Axis.NONE
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
                dragHandle = 0
                if (selecting) {
                    // Finger on a handle: drag that end of the mark
                    val (sr, sc, er, ec) = ordered()
                    val (hx1, hy1) = handlePos(sr, sc)
                    val (hx2, hy2) = handlePos(er, ec)
                    val reach = 28 * density
                    if (hypot(ev.x - hx2, ev.y - hy2 - 6 * density) < reach) dragHandle = 2
                    else if (hypot(ev.x - hx1, ev.y - hy1 - 6 * density) < reach) dragHandle = 1
                    if (dragHandle != 0) {
                        // Make the dragged end the focus
                        if (dragHandle == 1) { aRow = er; aCol = ec; fRow = sr; fCol = sc } else { aRow = sr; aCol = sc; fRow = er; fCol = ec }
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> scaling = true
            MotionEvent.ACTION_MOVE -> {
                if (dragHandle != 0 && !scaling) {
                    // A handle sits under its text line: aim slightly above the finger
                    val lift = if (dragHandle == 3) 0f else lineH * 0.8f + 6 * density
                    val (row, col) = hit(ev.x, ev.y - lift)
                    fRow = row; fCol = col
                    autoScroll(ev.x, ev.y)
                    invalidate()
                    return true
                }
                if (!scaling && ev.pointerCount == 1) {
                    if (axis == Axis.NONE) {
                        val dx = abs(ev.x - downX)
                        val dy = abs(ev.y - downY)
                        // Sideways starts at the touch slop, up/down needs a bit more and a clear direction
                        if (dx > slop && dx > dy) axis = Axis.H
                        else if (dy > slop * 1.6f && dy > dx * 1.2f) axis = Axis.V
                    }
                    if (axis != Axis.NONE) {
                        if (axis == Axis.H) scrollXf += lastX - ev.x else scrollYf += lastY - ev.y
                        clampScroll()
                        invalidate()
                        onRange?.invoke()
                    }
                }
                lastX = ev.x; lastY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragHandle != 0) {
                dragHandle = 0
                return true
            }
        }
        if (!scaling) gestures.onTouchEvent(ev) else if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // Cancel a pending long press when the second finger comes
            val c = MotionEvent.obtain(ev)
            c.action = MotionEvent.ACTION_CANCEL
            gestures.onTouchEvent(c)
            c.recycle()
        }
        return true
    }

    /** Scrolls while a handle is dragged near an edge. */
    private fun autoScroll(x: Float, y: Float) {
        val edge = 40 * density
        val step = lineH / 2
        when {
            y < edge -> scrollYf -= step
            y > height - edge -> scrollYf += step
        }
        when {
            x < edge -> scrollXf -= charW
            x > width - edge -> scrollXf += charW
        }
        clampScroll()
        onRange?.invoke()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXf = flingBaseX + scroller.currX
            scrollYf = flingBaseY + scroller.currY
            clampScroll()
            onRange?.invoke()
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) onColumns?.invoke()
        refresh()
    }

    companion object {
        const val MIN_SP = 7f
        const val MAX_SP = 36f
    }
}
