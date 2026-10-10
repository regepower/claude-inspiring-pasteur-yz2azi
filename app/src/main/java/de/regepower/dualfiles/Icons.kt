package de.regepower.dualfiles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * "Vivid" colours from the avatar tool: OKLCH, hue from the file extension, text at least 4.5:1 on the background.
 * Same maths as the HTML preview, so the phone shows the colours that were approved there.
 */
internal object VividColors {
    private const val BG_L = 0.58
    private const val BG_C = 0.16
    private const val FG_C = 0.07
    private const val TARGET = 4.5

    private val cache = HashMap<String, Pair<Int, Int>>()   // extension -> (background, text); UI thread only

    /** Background and text colour (ARGB) for an extension. */
    fun colorsFor(ext: String): Pair<Int, Int> = cache.getOrPut(ext) {
        val hue = (abs(hash(ext).toLong()) % 360).toDouble()
        var bgL = BG_L
        var bg = oklchToRgb(bgL, BG_C, hue)
        var fg = pickForeground(bg, hue)
        var i = 0
        while (fg == null && i < 60) {
            bgL -= 0.01
            if (bgL <= 0.05) break
            bg = oklchToRgb(bgL, BG_C, hue)
            fg = pickForeground(bg, hue)
            i++
        }
        val text = fg?.let { Color.rgb(it[0], it[1], it[2]) } ?: fallbackText(bg)
        Pair(Color.rgb(bg[0], bg[1], bg[2]), text)
    }

    // 32-bit wrap like the JavaScript version (h = (h << 5) - h + c; h |= 0)
    private fun hash(s: String): Int {
        var h = 0
        for (c in s) h = (h shl 5) - h + c.code
        return h
    }

    private fun toLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun toGamma(c: Double): Double {
        val v = c.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
    }

    private fun oklchToLinear(l: Double, c: Double, hDeg: Double): DoubleArray {
        val h = hDeg * PI / 180
        val a = c * cos(h)
        val b = c * sin(h)
        val l3 = l + 0.3963377774 * a + 0.2158037573 * b
        val m3 = l - 0.1055613458 * a - 0.0638541728 * b
        val s3 = l - 0.0894841775 * a - 1.2914855480 * b
        val lc = l3 * l3 * l3
        val mc = m3 * m3 * m3
        val sc = s3 * s3 * s3
        return doubleArrayOf(
            4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc,
            -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc,
            -0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc
        )
    }

    private fun inGamut(lin: DoubleArray) = lin.all { it >= -0.0001 && it <= 1.0001 }

    /** Reduces chroma by bisection until the colour is displayable in sRGB. */
    private fun oklchToRgb(l: Double, c: Double, hue: Double): IntArray {
        var chroma = c
        if (!inGamut(oklchToLinear(l, chroma, hue))) {
            var lo = 0.0
            var hi = chroma
            repeat(20) {
                val mid = (lo + hi) / 2
                if (inGamut(oklchToLinear(l, mid, hue))) lo = mid else hi = mid
            }
            chroma = lo
        }
        return oklchToLinear(l, chroma, hue).map { (toGamma(it) * 255).roundToInt() }.toIntArray()
    }

    /** Lightness of an sRGB colour in OKLCH. */
    private fun oklightness(rgb: IntArray): Double {
        val r = toLinear(rgb[0] / 255.0)
        val g = toLinear(rgb[1] / 255.0)
        val b = toLinear(rgb[2] / 255.0)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    }

    private fun luminance(rgb: IntArray): Double {
        val lin = rgb.map {
            val c = it / 255.0
            if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
    }

    private fun contrast(a: IntArray, b: IntArray): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    /** Lighter tint of the same hue that reaches the contrast target, or null. */
    private fun pickForeground(bg: IntArray, hue: Double): IntArray? {
        val bgL = oklightness(bg)
        for (step in 1..50) {
            val l = bgL + step * 0.02
            if (l <= 0.02 || l >= 0.995) break
            val rgb = oklchToRgb(l, FG_C, hue)
            if (contrast(rgb, bg) >= TARGET) return rgb
        }
        return null
    }

    private fun fallbackText(bg: IntArray): Int {
        val white = intArrayOf(255, 255, 255)
        val black = intArrayOf(17, 17, 17)
        return if (contrast(bg, white) >= contrast(bg, black)) Color.WHITE else Color.rgb(17, 17, 17)
    }
}

/** Font size so that the label fills the width of the file icon (max. 44 units of the 48-unit icon). */
internal object IconText {
    private val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 100f
    }
    private val sizes = HashMap<String, Float>()   // UI thread only

    fun sizeFor(label: String): Float = sizes.getOrPut(label) {
        val emWidth = measure.measureText(label) / 100f
        if (emWidth <= 0f) 44f else min(44f, 41f / emWidth)
    }
}

/**
 * Generated icon in the app's own style, drawn in a 48 x 56 unit box:
 * a file with folded corner and the extension inside, or a filled folder.
 * [selected] adds a check badge at the bottom right. A [thumb] (preview picture) replaces the file shape.
 */
internal class EntryIcon(
    private val label: String,
    private val isFolder: Boolean,
    private val fill: Int,
    private val textColor: Int,
    private val selected: Boolean,
    private val badge: Int,
    private val appBadge: AppBadge? = null,
    private val appTint: Int = 0,
    private val thumb: Bitmap? = null,
    private val checkColor: Int = Color.WHITE,
) : Drawable() {

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 48f, b.height() / 56f)

        if (thumb != null) {
            // Centre crop into the icon box with rounded corners
            val scale = max(48f / thumb.width, 56f / thumb.height)
            val w = 48f / scale
            val h = 56f / scale
            val src = android.graphics.Rect(
                ((thumb.width - w) / 2).toInt(), ((thumb.height - h) / 2).toInt(),
                ((thumb.width + w) / 2).toInt(), ((thumb.height + h) / 2).toInt()
            )
            canvas.save()
            canvas.clipPath(THUMB)
            canvas.drawBitmap(thumb, src, RectF(0f, 0f, 48f, 56f), badgePaint.apply { colorFilter = null })
            canvas.restore()
        } else {
            fillPaint.color = fill
            canvas.drawPath(if (isFolder) FOLDER else FILE, fillPaint)
        }
        if (!isFolder && thumb == null) {
            fillPaint.color = 0x38000000
            canvas.drawPath(FOLD, fillPaint)
            if (appBadge != null) {
                // The set app's symbol replaces the extension text: its silhouette in the text colour, or its colour icon
                if (appBadge.mono) {
                    badgePaint.colorFilter = PorterDuffColorFilter(appTint, PorterDuff.Mode.SRC_IN)
                    canvas.drawBitmap(appBadge.bitmap, null, RectF(10f, 19f, 38f, 47f), badgePaint)
                } else {
                    badgePaint.colorFilter = null
                    canvas.drawBitmap(appBadge.bitmap, null, RectF(11f, 20f, 37f, 46f), badgePaint)
                }
            } else if (label.isNotEmpty()) {
                textPaint.color = textColor
                textPaint.textSize = IconText.sizeFor(label)
                canvas.drawText(label, 24f, 36f + textPaint.textSize * 0.36f, textPaint)
            }
        }
        if (selected) {
            val cx = 40f
            val cy = 50f
            fillPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, 8.5f, fillPaint)
            fillPaint.color = badge
            canvas.drawCircle(cx, cy, 7f, fillPaint)
            val check = Path().apply {
                moveTo(cx - 3.5f, cy)
                lineTo(cx - 1f, cy + 2.8f)
                lineTo(cx + 3.8f, cy - 2.8f)
            }
            checkPaint.color = checkColor
            canvas.drawPath(check, checkPaint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        val FILE = Path().apply {
            moveTo(0f, 0f); lineTo(32f, 0f); lineTo(48f, 16f); lineTo(48f, 56f); lineTo(0f, 56f); close()
        }
        val THUMB = Path().apply { addRoundRect(RectF(0f, 0f, 48f, 56f), 6f, 6f, Path.Direction.CW) }
        val FOLD = Path().apply {
            moveTo(32f, 0f); lineTo(32f, 16f); lineTo(48f, 16f); close()
        }
        val FOLDER = Path().apply {
            moveTo(0f, 8f); lineTo(18f, 8f); lineTo(23f, 14f); lineTo(48f, 14f); lineTo(48f, 52f); lineTo(0f, 52f); close()
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
    }
}

/** Symbol of an app on a file icon: a white silhouette ([mono], drawn in the text colour) or the app's colour icon. */
internal class AppBadge(val bitmap: Bitmap, val mono: Boolean)

/**
 * Symbol of the app set for a file type. Tried in order, of the opening activity's icon, then the app icon:
 * the monochrome layer (Android 13+, made for this); the foreground or the whole icon as a silhouette, but
 * only if the shape says something (a filled blob, e.g. a multicoloured flower, does not); else the app's
 * colour icon. null = show the extension.
 */
internal object AppBadges {
    private const val SIZE = 48
    private const val BLOB = 0.62f   // share of its own bounds a shape may fill and still be recognisable
    private val cache = HashMap<String, AppBadge?>()   // UI thread only

    fun get(context: Context, cn: android.content.ComponentName): AppBadge? =
        cache.getOrPut(cn.flattenToString()) { render(context, cn) }

    @SuppressLint("NewApi")
    private fun render(context: Context, cn: android.content.ComponentName): AppBadge? {
        val pm = context.packageManager
        val icons = listOfNotNull(
            try { pm.getActivityIcon(cn) } catch (e: Exception) { null },
            try { pm.getApplicationIcon(cn.packageName) } catch (e: Exception) { null },
        )
        for (icon in icons) {
            val mono = if (icon is AdaptiveIconDrawable && Build.VERSION.SDK_INT >= 33) icon.monochrome else null
            mono?.let { silhouette(it, 1f) }?.let { return AppBadge(it, true) }
        }
        for (icon in icons) {
            val shape = if (icon is AdaptiveIconDrawable) icon.foreground else icon
            shape?.let { silhouette(it, BLOB) }?.let { return AppBadge(it, true) }
        }
        return icons.firstOrNull()?.let { colour(it) }?.let { AppBadge(it, false) }
    }

    /** The whole icon in its colours (an adaptive icon in the system's mask shape). */
    private fun colour(icon: Drawable): Bitmap? = try {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, SIZE, SIZE)
        icon.draw(Canvas(bmp))
        bmp
    } catch (e: Exception) {
        null
    }

    /**
     * The shape of [src] in white, or null if it is empty, a filled square, or fills more than [maxFill] of
     * its own bounds. Icon layers have a lot of empty margin (the launcher's safe zone), so the shape is
     * cut to its own bounds and scaled up: every badge fills the same space on the file icon.
     */
    private fun silhouette(src: Drawable, maxFill: Float): Bitmap? = try {
        val big = SIZE * 2
        val bmp = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888)
        src.setBounds(0, 0, big, big)
        src.draw(Canvas(bmp))
        val px = IntArray(big * big)
        bmp.getPixels(px, 0, big, 0, 0, big, big)
        var solid = 0
        var left = big
        var top = big
        var right = -1
        var bottom = -1
        for (i in px.indices) {
            val a = px[i] ushr 24
            if (a > 128) solid++
            if (a > 40) {
                val x = i % big
                val y = i / big
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
            px[i] = (a shl 24) or 0xFFFFFF
        }
        val share = solid.toFloat() / px.size
        val boxShare = if (right < left) 1f else solid.toFloat() / ((right - left + 1) * (bottom - top + 1))
        if (share < 0.03f || share > 0.8f || right < left || boxShare > maxFill) null
        else {
            bmp.setPixels(px, 0, big, 0, 0, big, big)
            // Square around the shape, centred, with a little air
            val side = (max(right - left, bottom - top) + 1) * 1.08f
            val cx = (left + right + 1) / 2f
            val cy = (top + bottom + 1) / 2f
            val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(
                bmp, android.graphics.Rect((cx - side / 2).roundToInt(), (cy - side / 2).roundToInt(), (cx + side / 2).roundToInt(), (cy + side / 2).roundToInt()),
                RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            bmp.recycle()
            out
        }
    } catch (e: Exception) {
        null
    }
}
