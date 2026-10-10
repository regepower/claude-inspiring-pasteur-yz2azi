package de.regepower.dualfiles

import android.app.Activity
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Read-only text and hex viewer. The file is read in 64 KB blocks only where it is shown, so large files
 * open at once. Text: lines are found in the background (a line longer than 4 KB continues in the next
 * row). Monospace; one finger scrolls up and down, two fingers move the lines sideways; both scroll bars
 * can be dragged.
 */
class ViewerActivity : Activity() {
    private lateinit var channel: FileChannel
    private var closer: Closeable? = null
    private var size = 0L
    private var hex = false
    private var charset: Charset = Charsets.UTF_8
    private val blocks = LruCache<Long, ByteArray>(32)   // block start -> bytes; UI thread only

    // Text rows (start offsets), filled by the indexer thread; count is published after the entries.
    @Volatile private var starts = LongArray(1024)
    @Volatile private var rowCount = 0
    @Volatile private var indexing = true
    @Volatile private var longestRow = 0
    @Volatile private var stop = false

    private lateinit var list: PanList
    private lateinit var info: TextView
    private lateinit var toggle: TextView
    private val adapter = RowAdapter()
    private val main = Handler(Looper.getMainLooper())
    private var charWidth = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val uri = intent.getStringExtra(EXTRA_URI)
        val name = intent.getStringExtra(EXTRA_NAME) ?: path?.let { File(it).name } ?: ""
        try {
            if (uri != null) {
                val pfd = contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: throw IOException()
                val input = FileInputStream(pfd.fileDescriptor)
                channel = input.channel
                closer = Closeable { input.close(); pfd.close() }
                size = if (pfd.statSize >= 0) pfd.statSize else channel.size()
            } else {
                val raf = RandomAccessFile(path ?: throw IOException(), "r")
                channel = raf.channel
                closer = raf
                size = raf.length()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, R.string.view_error, android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val head = bytes(0, 64 * 1024)
        hex = head.any { it == 0.toByte() }
        charset = if (isUtf8(head)) Charsets.UTF_8 else Charsets.ISO_8859_1
        setContentView(build(name))
        Thread { index() }.start()
        show()
    }

    override fun onDestroy() {
        stop = true
        try {
            closer?.close()
        } catch (e: IOException) {
            // nothing left to do
        }
        super.onDestroy()
    }

    private fun build(name: String): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(getColor(R.color.md_surface))
        root.setOnApplyWindowInsetsListener { v, insets ->
            val s = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(16), dp(6), dp(8), dp(6))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = name
        title.textSize = 17f
        title.typeface = Typeface.DEFAULT_BOLD
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        title.setTextColor(getColor(R.color.md_on_container))
        info = TextView(this)
        info.textSize = 12f
        info.setTextColor(getColor(R.color.md_on_surface_variant))
        texts.addView(title)
        texts.addView(info)
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toggle = TextView(this)
        toggle.textSize = 13f
        toggle.typeface = Typeface.DEFAULT_BOLD
        toggle.gravity = Gravity.CENTER
        toggle.setPadding(dp(14), dp(8), dp(14), dp(8))
        toggle.setTextColor(getColor(R.color.md_on_container))
        toggle.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(getColor(R.color.md_container))
        }
        toggle.setOnClickListener {
            hex = !hex
            list.offset = 0
            list.setSelection(0)
            show()
        }
        head.addView(toggle)
        root.addView(head)

        list = PanList(this)
        list.adapter = adapter
        list.divider = null
        list.isFastScrollEnabled = true          // draggable vertical thumb
        list.isFastScrollAlwaysVisible = true
        list.setPadding(0, 0, dp(12), 0)
        list.clipToPadding = false
        val bar = RangeBar(this)
        bar.onDrag = { list.shiftNames(it) }
        list.onRange = { content, viewport, offset -> bar.update(content, viewport, offset) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))

        charWidth = android.graphics.Paint().apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f * resources.displayMetrics.scaledDensity
        }.measureText("0")
        return root
    }

    /** Refreshes counts, width and labels after a mode change or new index data. */
    private fun show() {
        toggle.setText(if (hex) R.string.view_text else R.string.view_hex)
        val chars = if (hex) hexWidth() else longestRow + 1
        list.contentWidth = chars * charWidth + dp(16)
        val sizeText = Formatter.formatFileSize(this, size)
        info.text = if (hex) getString(R.string.view_info_hex, sizeText)
        else getString(if (indexing) R.string.view_info_reading else R.string.view_info_text, sizeText, rowCount, charset.name())
        adapter.notifyDataSetChanged()
        list.requestLayout()
    }

    // ---- File access ----

    /** [len] bytes from [pos] (fewer at the end of the file), through the block cache. */
    private fun bytes(pos: Long, len: Int): ByteArray {
        val out = ByteArray(maxOf(0, minOf(len.toLong(), size - pos).toInt()))
        var done = 0
        while (done < out.size) {
            val p = pos + done
            val blockStart = p / BLOCK * BLOCK
            val block = blocks.get(blockStart) ?: readAt(blockStart, BLOCK).also { blocks.put(blockStart, it) }
            val from = (p - blockStart).toInt()
            val n = minOf(out.size - done, block.size - from)
            if (n <= 0) break
            System.arraycopy(block, from, out, done, n)
            done += n
        }
        return if (done == out.size) out else out.copyOf(done)
    }

    private fun readAt(pos: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        try {
            while (buf.hasRemaining()) {
                val n = channel.read(buf, pos + buf.position())
                if (n <= 0) break
            }
        } catch (e: IOException) {
            // shows what could be read
        }
        return buf.array().copyOf(buf.position())
    }

    private fun isUtf8(b: ByteArray): Boolean {
        // Ignore a sequence cut off at the end of the sample
        var end = b.size
        var back = 0
        while (end > 0 && back < 3 && (b[end - 1].toInt() and 0xC0) == 0x80) { end--; back++ }
        if (end > 0 && (b[end - 1].toInt() and 0x80) != 0) end--
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, 0, end))
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }

    /** Finds the text rows on a background thread, with its own file handle position reads. */
    private fun index() {
        val buf = ByteBuffer.allocate(1 shl 20)
        var pos = 0L
        var rowStart = 0L
        var lastPublish = 0L
        add(0)
        try {
            while (pos < size && !stop) {
                buf.clear()
                val n = channel.read(buf, pos)
                if (n <= 0) break
                val a = buf.array()
                for (i in 0 until n) {
                    val abs = pos + i
                    val b = a[i].toInt()
                    if (b == '\n'.code) {
                        longestRow = maxOf(longestRow, (abs - rowStart).toInt())
                        rowStart = abs + 1
                        if (rowStart < size && !add(rowStart)) return finishIndex()
                    } else if (abs - rowStart >= MAX_ROW && (b and 0xC0) != 0x80) {
                        // Very long line: continue in a new row, never inside a UTF-8 character
                        longestRow = MAX_ROW
                        rowStart = abs
                        if (!add(rowStart)) return finishIndex()
                    }
                }
                pos += n
                val now = System.currentTimeMillis()
                if (now - lastPublish > 300) {
                    lastPublish = now
                    main.post { if (!isFinishing) show() }
                }
            }
            longestRow = maxOf(longestRow, (size - rowStart).toInt().coerceAtMost(MAX_ROW))
        } catch (e: IOException) {
            // keep the rows found so far
        }
        finishIndex()
    }

    private fun finishIndex() {
        indexing = false
        main.post { if (!isFinishing) show() }
    }

    /** Adds a row start; false when the row limit is reached. */
    private fun add(start: Long): Boolean {
        val n = rowCount
        if (n >= MAX_ROWS) return false
        var a = starts
        if (n == a.size) {
            a = a.copyOf(a.size * 2)
            starts = a                       // new array first, then the count that needs it
        }
        a[n] = start
        rowCount = n + 1
        return true
    }

    // ---- Rows ----

    private fun hexDigits() = if (size > 0xFFFFFFFFL) 10 else 8

    private fun hexWidth() = hexDigits() + 2 + 16 * 3 + 1 + 16

    private fun hexRows(): Int = minOf((size + 15) / 16, Int.MAX_VALUE.toLong()).toInt()

    private fun textRow(i: Int): String {
        val a = starts
        val start = a[i]
        val end = if (i + 1 < rowCount) a[i + 1] else size
        var b = bytes(start, (end - start).toInt().coerceAtMost(MAX_ROW + 4))
        var len = b.size
        while (len > 0 && (b[len - 1] == '\n'.code.toByte() || b[len - 1] == '\r'.code.toByte())) len--
        if (len != b.size) b = b.copyOf(len)
        val s = String(b, charset)
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '\t' -> {
                    do sb.append(' ') while (sb.length % 4 != 0)
                }
                c < ' ' -> sb.append('·')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun hexRow(i: Int): String {
        val pos = i * 16L
        val b = bytes(pos, 16)
        val sb = StringBuilder(hexWidth())
        val off = java.lang.Long.toHexString(pos).uppercase()
        repeat(hexDigits() - off.length) { sb.append('0') }
        sb.append(off).append("  ")
        for (k in 0 until 16) {
            if (k < b.size) {
                val v = b[k].toInt() and 0xFF
                sb.append(HEX[v shr 4]).append(HEX[v and 15]).append(' ')
            } else sb.append("   ")
            if (k == 7) sb.append(' ')
        }
        for (k in b.indices) {
            val v = b[k].toInt() and 0xFF
            sb.append(if (v in 0x20..0x7E) v.toChar() else '.')
        }
        return sb.toString()
    }

    private class Row(ctx: android.content.Context) : FrameLayout(ctx), NameRow {
        val text = TextView(ctx)
        override val clip = WideClip(ctx)
        override val content: View get() = text

        init {
            text.typeface = Typeface.MONOSPACE
            text.textSize = 13f
            text.maxLines = 1
            text.setHorizontallyScrolling(true)
            text.setPadding(ctx.dp(8), ctx.dp(1), ctx.dp(8), ctx.dp(1))
            clip.addView(text, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(clip, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = if (hex) hexRows() else rowCount
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? Row ?: Row(this@ViewerActivity).also {
                it.text.setTextColor(getColor(R.color.md_on_surface))
            }
            row.text.text = if (hex) hexRow(position) else textRow(position)
            list.applyTo(row)
            return row
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        private const val BLOCK = 64 * 1024
        private const val MAX_ROW = 4096               // bytes per text row before it continues
        private const val MAX_ROWS = 4_000_000         // 32 MB of row offsets at most
        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}
