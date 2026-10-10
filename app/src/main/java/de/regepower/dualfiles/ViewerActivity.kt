package de.regepower.dualfiles

import android.app.Activity
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
 * row). Monospace; one finger scrolls both ways, two fingers zoom, hold a word to mark and copy
 * (text and hex). Both scroll bars can be dragged.
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

    private lateinit var grid: TextGrid
    private lateinit var vBar: RangeBar
    private lateinit var hBar: RangeBar
    private lateinit var info: TextView
    private lateinit var toggle: TextView
    private lateinit var selBar: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

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
            Toast.makeText(this, R.string.view_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val head = bytes(0, 64 * 1024)
        hex = head.any { it == 0.toByte() }
        charset = if (isUtf8(head)) Charsets.UTF_8 else Charsets.ISO_8859_1
        setContentView(build(name))
        // Android 13+: back comes through the dispatcher; it first removes a mark
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (grid.selecting) grid.clearSelection() else finish()
            }
        }
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

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Back first removes a mark
        if (::grid.isInitialized && grid.selecting) grid.clearSelection() else super.onBackPressed()
    }

    private fun chip(label: Int, onClick: () -> Unit) = TextView(this).apply {
        setText(label)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setTextColor(getColor(R.color.md_on_container))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(getColor(R.color.md_container))
        }
        setOnClickListener { onClick() }
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
        toggle = chip(R.string.view_hex) {
            hex = !hex
            grid.reset()
            show()
        }
        head.addView(toggle)
        // ⋮: line numbers and wrapping (text mode)
        val more = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.md_on_container))
            val sel = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, sel, true)
            setBackgroundResource(sel.resourceId)
            contentDescription = getString(R.string.more)
            setOnClickListener { showOptions(it) }
        }
        head.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(head)

        // Shown while something is marked
        selBar = LinearLayout(this)
        selBar.gravity = Gravity.CENTER_VERTICAL
        selBar.setPadding(dp(12), 0, dp(8), dp(6))
        val gap = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.setMargins(dp(4), 0, dp(4), 0) } }
        selBar.addView(chip(R.string.view_copy) { copySelection(false) }, gap())
        selBar.addView(chip(R.string.share) { copySelection(true) }, gap())
        selBar.addView(chip(R.string.view_select_all) { grid.selectAll() }, gap())
        selBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        selBar.addView(chip(R.string.view_unmark) { grid.clearSelection() }, gap())
        selBar.visibility = View.GONE
        root.addView(selBar)

        grid = TextGrid(this)
        grid.setColors(
            getColor(R.color.md_on_surface), getColor(R.color.md_primary) and 0x50FFFFFF, getColor(R.color.md_primary),
            getColor(R.color.md_surface), getColor(R.color.md_on_surface_variant),
        )
        numbers = prefs.getBoolean("viewer_numbers", false)
        wrap = prefs.getBoolean("viewer_wrap", false)
        grid.onColumns = { if (wrap && !hex) rebuildWrap() }
        grid.setTextSizeSp(prefs.getFloat("viewer_sp", 13f))
        grid.onZoom = { prefs.edit().putFloat("viewer_sp", it).apply() }
        grid.onSelection = { on -> selBar.visibility = if (on) View.VISIBLE else View.GONE }
        grid.source = object : TextGrid.Source {
            override fun count() = when {
                hex -> hexRows()
                wrapping() -> wrapCount
                else -> rowCount
            }
            override fun row(i: Int): String = when {
                hex -> hexRow(i)
                wrapping() -> cachedRow(wrapRow[i]).let { t -> t.substring(minOf(wrapCol[i], t.length), minOf(wrapCol[i] + wrapCols, t.length)) }
                else -> cachedRow(i)
            }
            override fun maxChars() = when {
                hex -> hexWidth()
                wrapping() -> wrapCols
                else -> longestRow + 1
            }
            override fun label(i: Int): String = when {
                hex || !numbers -> ""
                wrapping() -> if (wrapCol[i] == 0) (wrapRow[i] + 1).toString() else ""
                else -> (i + 1).toString()
            }
            override fun joinsNext(i: Int) = wrapping() && i + 1 < wrapCount && wrapRow[i + 1] == wrapRow[i]
        }
        vBar = RangeBar(this)
        vBar.vertical = true
        hBar = RangeBar(this)
        // The vertical bar works in rows (a hex view can have more pixels than an Int holds)
        vBar.onDrag = { row -> grid.scrollToPos(grid.scrollXf, row.toDouble() * (grid.contentHeight() / maxOf(1, grid.source!!.count()))) }
        hBar.onDrag = { x -> grid.scrollToPos(x.toDouble(), grid.scrollYf) }
        grid.onRange = {
            vBar.update(grid.source!!.count(), grid.rowsVisible(), grid.firstRow())
            hBar.update(grid.contentWidth().toInt(), grid.width, grid.scrollXf.toInt())
        }

        val row = LinearLayout(this)
        row.addView(grid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(vBar, LinearLayout.LayoutParams(dp(14), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(hBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)))
        return root
    }

    /** Refreshes counts, width and labels after a mode change or new index data. */
    private fun show() {
        toggle.setText(if (hex) R.string.view_text else R.string.view_hex)
        grid.gutterChars = if (numbers && !hex) maxOf(2, rowCount.toString().length) else 0
        if (wrapping() && wrapBuiltFor != rowCount) rebuildWrap()
        val sizeText = Formatter.formatFileSize(this, size)
        info.text = if (hex) getString(R.string.view_info_hex, sizeText)
        else getString(if (indexing) R.string.view_info_reading else R.string.view_info_text, sizeText, rowCount, charset.name())
        grid.refresh()
    }

    // ---- Line numbers and wrapping ----

    private var numbers = false
    private var wrap = false
    private var wrapRow = IntArray(0)   // visual line -> text row
    private var wrapCol = IntArray(0)   // visual line -> first character in that row
    private var wrapCount = 0
    private var wrapCols = 80
    private var wrapBuiltFor = -1       // rowCount the wrapping was made for
    private val rowCache = LruCache<Int, String>(256)

    private fun wrapping() = wrap && !hex && size <= WRAP_LIMIT

    private fun cachedRow(i: Int): String = rowCache.get(i) ?: textRow(i).also { rowCache.put(i, it) }

    /** Splits every text row into lines that fit the width (files up to [WRAP_LIMIT]). */
    private fun rebuildWrap() {
        if (!wrapping()) return
        val cols = grid.columns()
        var rows = IntArray(maxOf(16, rowCount + rowCount / 4))
        var colsAt = IntArray(rows.size)
        var n = 0
        for (r in 0 until rowCount) {
            val len = cachedRow(r).length
            var c = 0
            do {
                if (n == rows.size) {
                    rows = rows.copyOf(n * 2)
                    colsAt = colsAt.copyOf(n * 2)
                }
                rows[n] = r
                colsAt[n] = c
                n++
                c += cols
            } while (c < len)
        }
        wrapRow = rows
        wrapCol = colsAt
        wrapCount = n
        wrapCols = cols
        wrapBuiltFor = rowCount
        grid.refresh()
    }

    private fun showOptions(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.view_numbers).apply { isCheckable = true; isChecked = numbers; isEnabled = !hex }
        menu.menu.add(0, 2, 1, R.string.view_wrap).apply { isCheckable = true; isChecked = wrap; isEnabled = !hex }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    numbers = !numbers
                    prefs.edit().putBoolean("viewer_numbers", numbers).apply()
                }
                2 -> {
                    if (!wrap && size > WRAP_LIMIT) {
                        Toast.makeText(this, getString(R.string.view_wrap_limit, WRAP_LIMIT / 1_000_000), Toast.LENGTH_LONG).show()
                        return@setOnMenuItemClickListener true
                    }
                    wrap = !wrap
                    prefs.edit().putBoolean("viewer_wrap", wrap).apply()
                    wrapBuiltFor = -1
                    grid.reset()
                }
            }
            show()
            if (wrapping()) grid.post { rebuildWrap() }
            true
        }
        menu.show()
    }

    /** Copies (or shares) the marked text; the toast shows what was copied. */
    private fun copySelection(share: Boolean) {
        val text = grid.selectedText(MAX_COPY)
        if (text == null) {
            Toast.makeText(this, getString(R.string.view_too_big, MAX_COPY / 1_000_000), Toast.LENGTH_LONG).show()
            return
        }
        if (share) {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, text)
            startActivity(android.content.Intent.createChooser(send, getString(R.string.share)))
            return
        }
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText(null, text))
        val preview = text.replace('\n', ' ').let { if (it.length > 40) it.take(40) + "…" else it }
        Toast.makeText(this, getString(R.string.view_copied_what, preview, text.length), Toast.LENGTH_SHORT).show()
        grid.clearSelection()
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


    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        private const val BLOCK = 64 * 1024
        private const val MAX_COPY = 2_000_000         // characters that can be copied at once
        private const val WRAP_LIMIT = 4_000_000L      // wrapping reads every line once: only for files up to this size
        private const val MAX_ROW = 4096               // bytes per text row before it continues
        private const val MAX_ROWS = 4_000_000         // 32 MB of row offsets at most
        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}
