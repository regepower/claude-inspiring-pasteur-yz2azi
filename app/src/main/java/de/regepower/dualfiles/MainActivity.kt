package de.regepower.dualfiles

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Date
import java.util.concurrent.Executors

private const val SRC_COLOR = 0xFF1F5FBF.toInt()
private const val DST_COLOR = 0xFFB45309.toInt()
private const val ERROR_COLOR = 0xFFB3261E.toInt()

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

private enum class SortBy(val label: Int) { NAME(R.string.sort_name), DATE(R.string.sort_date), SIZE(R.string.sort_size), TYPE(R.string.sort_type) }

private enum class Filter(val label: Int, val exts: Set<String>? = null) {
    ALL(R.string.filter_all),
    FOLDERS(R.string.filter_folders),
    FILES(R.string.filter_files),
    IMAGES(R.string.filter_images, setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg")),
    VIDEO(R.string.filter_video, setOf("mp4", "mkv", "avi", "mov", "webm", "3gp")),
    AUDIO(R.string.filter_audio, setOf("mp3", "m4a", "ogg", "wav", "flac", "opus", "aac")),
    DOCS(R.string.filter_docs, setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "odt", "rtf", "csv")),
}

/** One row of the folder tree; [width] is the name's width in px. */
private class Node(val file: File, val depth: Int, val label: String, val width: Float)

/** One row of the file list; [up] marks the ".." row, whose [file] is the parent folder. */
private class Entry(
    val file: File,
    val up: Boolean,
    val isDir: Boolean = false,
    val size: Long = 0,
    val modified: Long = 0,
) {
    val key = file.name.lowercase()
    var meta = ""       // size · date (files); folders get their item count in the row
    var width = 0f      // widest text of the row in px, for the horizontal range
}

/** One side (source or target): current folder, selection, tree state. */
private class Pane(val color: Int, val bandRes: Int, var dir: File) {
    val selected = LinkedHashSet<File>()
    val expanded = HashSet<String>()
    var nodes: List<Node> = emptyList()
    var entries: List<Entry> = emptyList()
    lateinit var treeBand: TextView
    lateinit var fileBand: TextView
    lateinit var treeAdapter: BaseAdapter
    lateinit var fileAdapter: BaseAdapter
    lateinit var treeList: PanList
    lateinit var fileList: PanList
    var treePage = 0
    var filePage = 0
    var gen = 0
    var raw: List<Entry> = emptyList()   // as read from disk
    var sortBy = SortBy.NAME
    var sortDesc = false
    var filter = Filter.ALL
    var nameQuery = ""
    lateinit var sortChip: TextView
    lateinit var filterChip: TextView
}

/** A list row whose name can be moved sideways; [clip] is the visible part of the name. */
private interface NameRow {
    val clip: View
    val content: View
}

/**
 * ListView with one shared sideways offset for all names. A two-finger drag moves it; one finger
 * keeps scrolling the list and swiping the pages. [onRange] tells the scroll bar about the range.
 */
private class PanList(context: Context) : ListView(context) {
    var contentWidth = 0f          // widest name of the list in px
    var offset = 0                 // px, the same for every visible row
    var onRange: ((Int, Int) -> Unit)? = null
    private var ignore = false
    private var two = false
    private var startX = 0f
    private var startOffset = 0

    private fun meanX(ev: MotionEvent) = (ev.getX(0) + ev.getX(1)) / 2f

    /** Visible width of the names (narrowest visible row), 0 before the first layout. */
    private fun viewportWidth(): Int {
        var w = Int.MAX_VALUE
        for (i in 0 until childCount) {
            val r = getChildAt(i) as? NameRow ?: continue
            w = minOf(w, r.clip.width)
        }
        return if (w == Int.MAX_VALUE) 0 else w
    }

    fun maxOffset(): Int = maxOf(0, (contentWidth - viewportWidth()).toInt())

    fun setOffset(x: Int) {
        offset = x.coerceIn(0, maxOffset())
        applyOffset()
    }

    /** Puts a (re)used row at the shared offset. */
    fun applyTo(row: NameRow) {
        row.content.translationX = -offset.toFloat()
    }

    fun applyOffset() {
        for (i in 0 until childCount) (getChildAt(i) as? NameRow)?.let { applyTo(it) }
        onRange?.invoke(maxOffset(), offset)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        offset = offset.coerceIn(0, maxOffset())
        applyOffset()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignore = false
                two = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!ignore && ev.pointerCount == 2) {
                ignore = true
                two = true
                // Cancel the one-finger press for the list itself (no scroll, no click, no long press).
                val c = MotionEvent.obtain(ev.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, ev.getX(0), ev.getY(0), 0)
                super.dispatchTouchEvent(c)
                c.recycle()
                parent?.requestDisallowInterceptTouchEvent(true)
                startX = meanX(ev)
                startOffset = offset
                return true
            }
            MotionEvent.ACTION_MOVE -> if (ignore) {
                if (two && ev.pointerCount >= 2) {
                    offset = (startOffset - (meanX(ev) - startX)).toInt().coerceIn(0, maxOffset())
                    applyOffset()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> if (ignore) {
                two = false
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (ignore) {
                ignore = false
                two = false
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

private class TreeRow(ctx: Context) : LinearLayout(ctx), NameRow {
    val chevron = TextView(ctx)
    val icon = ImageView(ctx)
    val label = TextView(ctx)
    override val clip = FrameLayout(ctx)
    override val content: View get() = label

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = rowParams(ctx.dp(48))
        chevron.gravity = Gravity.CENTER
        chevron.textSize = 14f
        addView(chevron, LayoutParams(ctx.dp(36), ViewGroup.LayoutParams.MATCH_PARENT))
        icon.scaleType = ImageView.ScaleType.FIT_XY
        addView(icon, LayoutParams(ctx.dp(22), ctx.dp(26)))
        label.textSize = 15f
        label.maxLines = 1
        label.setPadding(ctx.dp(8), 0, ctx.dp(12), 0)
        clip.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL))
        addView(clip, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }
}

private class FileRow(ctx: Context) : LinearLayout(ctx), NameRow {
    val iconHit = FrameLayout(ctx)     // the icon is the check box: tap = mark, rest of the row = open
    val icon = ImageView(ctx)
    override val clip = FrameLayout(ctx)
    val inner = LinearLayout(ctx)
    override val content: View get() = inner
    val name = TextView(ctx)
    val meta = TextView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = rowParams(ctx.dp(56))
        setPadding(0, 0, ctx.dp(14), 0)
        icon.scaleType = ImageView.ScaleType.FIT_XY
        iconHit.addView(icon, FrameLayout.LayoutParams(ctx.dp(38), ctx.dp(44), Gravity.CENTER))
        addView(iconHit, LayoutParams(ctx.dp(48), ViewGroup.LayoutParams.MATCH_PARENT))
        inner.orientation = VERTICAL
        inner.setPadding(ctx.dp(8), 0, ctx.dp(12), 0)
        name.textSize = 15f
        name.maxLines = 1
        meta.textSize = 12f
        meta.maxLines = 1
        inner.addView(name)
        inner.addView(meta)
        clip.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL))
        addView(clip, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }
}

private fun rowParams(h: Int) = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)

class MainActivity : Activity() {
    private lateinit var panes: Array<Pane>
    private lateinit var pager: SnapScroll
    private lateinit var mainView: View
    private lateinit var permView: View
    private lateinit var bar: LinearLayout
    private lateinit var barCount: TextView
    private val tabs = ArrayList<TextView>()
    private var roots: List<File> = emptyList()
    private val rootNames = HashMap<String, String>()
    private val loader = Executors.newSingleThreadExecutor()   // directory reads, off the UI thread
    private val counter = Executors.newSingleThreadExecutor()  // folder item counts
    private val counts = HashMap<String, Int>()                // UI thread only
    private val countsPending = HashSet<String>()              // UI thread only
    private var backAt = 0L

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sm = getSystemService(StorageManager::class.java)
        val vols = sm.storageVolumes.filter { it.directory != null && it.state == Environment.MEDIA_MOUNTED }
        roots = vols.mapNotNull { it.directory }
        for (v in vols) rootNames[v.directory!!.path] = v.getDescription(this)
        val primary = roots.firstOrNull() ?: Environment.getExternalStorageDirectory()
        val download = File(primary, "Download")
        panes = arrayOf(
            Pane(SRC_COLOR, R.string.band_source, if (download.isDirectory) download else primary).also {
                it.treePage = 0
                it.filePage = 1
            },
            Pane(DST_COLOR, R.string.band_target, primary).also {
                it.treePage = 3
                it.filePage = 2
            }
        )
        for (p in panes) expandTo(p, p.dir)

        val root = FrameLayout(this)
        root.setBackgroundColor(getColor(R.color.md_surface))
        root.setOnApplyWindowInsetsListener { v, insets ->
            val s = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        mainView = buildMain()
        permView = buildPermission()
        root.addView(mainView)
        root.addView(permView)
        setContentView(root)
        // Android 13+ delivers back through the dispatcher (predictive back); older versions use onBackPressed().
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() = handleBack()

    /** Back: one folder up in the pane of the visible page; at the top level, a second press exits. */
    private fun handleBack() {
        if (mainView.visibility != View.VISIBLE) return finish()
        val idx = Math.round(pager.scrollX / pager.pageWidth.toFloat()).coerceIn(0, 3)
        val p = panes[if (idx < 2) 0 else 1]
        val parent = p.dir.parentFile
        if (roots.none { it.path == p.dir.path } && parent != null) {
            open(p, parent)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - backAt < 2000) return finish()
        backAt = now
        Toast.makeText(this, R.string.back_again, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        val ok = Environment.isExternalStorageManager()
        mainView.visibility = if (ok) View.VISIBLE else View.GONE
        permView.visibility = if (ok) View.GONE else View.VISIBLE
        if (ok) refreshAll()
    }

    // ---- UI construction ----

    private fun buildMain(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        // Header row (same in all our apps, without config save/load): app name left, help right.
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(16), dp(4), dp(8), dp(4))
        val title = TextView(this)
        title.setText(R.string.app_name)
        title.textSize = 24f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(getColor(R.color.md_on_container))
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val help = ImageButton(this)
        help.setImageResource(R.drawable.ic_help)
        help.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.md_primary))
        val sel = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, sel, true)
        help.setBackgroundResource(sel.resourceId)
        help.contentDescription = getString(R.string.help)
        help.tooltipText = getString(R.string.help)
        help.setOnClickListener { Help.show(this) }
        head.addView(help, LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(head)

        val tabRow = LinearLayout(this)
        tabRow.setPadding(dp(10), dp(4), dp(10), dp(8))
        val labels = intArrayOf(R.string.tab_folders, R.string.tab_source, R.string.tab_target, R.string.tab_folders)
        for (i in labels.indices) {
            val t = TextView(this)
            t.setText(labels[i])
            t.gravity = Gravity.CENTER
            t.textSize = 12f
            t.typeface = Typeface.DEFAULT_BOLD
            t.setOnClickListener { pager.snapTo(i) }
            val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            tabRow.addView(t, lp)
            tabs.add(t)
        }
        col.addView(tabRow)

        val pageW = resources.displayMetrics.widthPixels
        pager = SnapScroll(this)
        pager.pageWidth = pageW
        pager.pageCount = 4
        pager.onPage = { setTab(it) }
        val strip = LinearLayout(this)
        val pages = arrayOf(
            buildTree(panes[0]), buildFiles(panes[0]), buildFiles(panes[1]), buildTree(panes[1])
        )
        for (pg in pages) strip.addView(pg, LinearLayout.LayoutParams(pageW, ViewGroup.LayoutParams.MATCH_PARENT))
        pager.addView(strip, ViewGroup.LayoutParams(pageW * 4, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(getColor(R.color.md_container))
        bar.setPadding(dp(10), dp(6), dp(10), dp(6))
        barCount = TextView(this)
        barCount.textSize = 12f
        barCount.typeface = Typeface.DEFAULT_BOLD
        bar.addView(barCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(actionButton(R.string.copy, 0, null))
        bar.addView(actionButton(R.string.move, 1, null))
        bar.addView(actionButton(R.string.delete, 2, ERROR_COLOR))
        bar.visibility = View.GONE
        col.addView(bar)

        setTab(0)
        return col
    }

    private fun actionButton(text: Int, which: Int, color: Int?): Button {
        val b = Button(this)
        b.setText(text)
        b.isAllCaps = false
        if (color != null) b.setTextColor(color)
        b.setOnClickListener { act(which) }
        return b
    }

    private fun band(p: Pane): TextView {
        val t = TextView(this)
        t.setBackgroundColor(p.color)
        t.setTextColor(Color.WHITE)
        t.textSize = 12f
        t.typeface = Typeface.DEFAULT_BOLD
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.MIDDLE
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        return t
    }

    private fun <T : ListView> styledList(l: T): T {
        l.divider = ColorDrawable(getColor(R.color.md_outline) and 0x55FFFFFF)
        l.dividerHeight = 1
        return l
    }

    /** Scroll bar under a list: visible only when the names are wider than the list; dragging it scrolls the names. */
    private fun rangeBar(list: PanList): SeekBar {
        val bar = SeekBar(this)
        bar.visibility = View.GONE
        bar.setPadding(dp(12), 0, dp(12), 0)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) list.setOffset(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        list.onRange = { max, off ->
            bar.max = max
            if (bar.progress != off) bar.progress = off
            bar.visibility = if (max > 0) View.VISIBLE else View.GONE
        }
        return bar
    }

    private fun buildTree(p: Pane): View {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        p.treeBand = band(p)
        page.addView(p.treeBand)
        val list = PanList(this)
        styledList(list)
        p.treeList = list
        p.treeAdapter = TreeAdapter(p)
        list.adapter = p.treeAdapter
        list.setOnItemClickListener { _, _, pos, _ ->
            open(p, p.nodes[pos].file)
            pager.snapTo(p.filePage)
        }
        page.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        page.addView(rangeBar(list))
        return page
    }

    private fun buildFiles(p: Pane): View {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        p.fileBand = band(p)
        page.addView(p.fileBand)
        val chips = LinearLayout(this)
        chips.setPadding(dp(6), dp(6), dp(6), dp(6))
        p.sortChip = chip { showSortDialog(p) }
        p.filterChip = chip { showFilterDialog(p) }
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(dp(3), 0, dp(3), 0) } }
        chips.addView(p.sortChip, lp())
        chips.addView(p.filterChip, lp())
        page.addView(chips)
        val list = PanList(this)
        styledList(list)
        p.fileList = list
        p.fileAdapter = FileAdapter(p)
        list.adapter = p.fileAdapter
        // One tap opens (folder, or file in its default app); marking is only done with the icon.
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = p.entries[pos]
            if (e.up || e.isDir) open(p, e.file) else openFile(e.file)
        }
        // Long press: menu for the held entry (it gets marked, so copy/move/delete apply to it).
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val e = p.entries[pos]
            if (!e.up) {
                if (!p.selected.contains(e.file)) toggle(p, e.file)
                showMenu(e.file)
            }
            true
        }
        page.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        page.addView(rangeBar(list))
        return page
    }

    private fun buildPermission(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setPadding(dp(32), dp(32), dp(32), dp(32))
        val text = TextView(this)
        text.setText(R.string.perm_text)
        text.textSize = 16f
        text.gravity = Gravity.CENTER
        col.addView(text)
        val b = Button(this)
        b.setText(R.string.perm_button)
        b.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        col.addView(b)
        col.visibility = View.GONE
        return col
    }

    private fun setTab(active: Int) {
        for (i in tabs.indices) {
            val on = i == active
            val color = if (i < 2) SRC_COLOR else DST_COLOR
            val bg = GradientDrawable()
            bg.cornerRadius = dp(8).toFloat()
            bg.setColor(if (on) color else getColor(R.color.md_container))
            tabs[i].background = bg
            tabs[i].setTextColor(if (on) Color.WHITE else getColor(R.color.md_on_container))
        }
    }

    private fun chip(onClick: () -> Unit) = TextView(this).apply {
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setTextColor(getColor(R.color.md_on_container))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(getColor(R.color.md_container))
        }
        setOnClickListener { onClick() }
    }

    private fun updateChips(p: Pane) {
        val arrow = if (p.sortDesc) "↓" else "↑"
        p.sortChip.text = getString(R.string.chip_sort, getString(p.sortBy.label), arrow)
        val name = if (p.nameQuery.isEmpty()) "" else " · \"${p.nameQuery}\""
        p.filterChip.text = getString(R.string.chip_filter, getString(p.filter.label), name)
    }

    // ---- State ----

    private fun expandTo(p: Pane, dir: File) {
        var f: File? = dir
        while (f != null) {
            p.expanded.add(f.path)
            f = f.parentFile
        }
    }

    private fun open(p: Pane, dir: File) {
        if (!dir.canRead()) return
        p.dir = dir
        p.selected.clear()
        expandTo(p, dir)
        p.treeList.offset = 0
        p.fileList.offset = 0
        loadPane(p)
        updateBar()
    }

    private fun toggle(p: Pane, f: File) {
        if (!p.selected.remove(f)) p.selected.add(f)
        for (other in panes) if (other !== p) other.selected.clear()
        refreshSelection()
    }

    /** Sort and filter of the in-memory list: no disk access. Folders always come first. */
    private fun viewOf(p: Pane): List<Entry> {
        val cmp: Comparator<Entry> = when (p.sortBy) {
            SortBy.NAME -> compareBy<Entry> { it.key }
            SortBy.DATE -> compareBy<Entry> { it.modified }
            SortBy.SIZE -> compareBy<Entry> { it.size }
            SortBy.TYPE -> compareBy<Entry>({ it.file.extension.lowercase() }, { it.key })
        }
        val items = p.raw.filter { !it.up && matches(it, p) }
            .sortedWith(compareBy<Entry> { !it.isDir }.then(if (p.sortDesc) cmp.reversed() else cmp))
        return p.raw.filter { it.up } + items
    }

    private fun matches(e: Entry, p: Pane): Boolean {
        if (p.nameQuery.isNotEmpty() && !e.key.contains(p.nameQuery.lowercase())) return false
        return when (p.filter) {
            Filter.ALL -> true
            Filter.FOLDERS -> e.isDir
            Filter.FILES -> !e.isDir
            else -> !e.isDir && p.filter.exts?.contains(e.file.extension.lowercase()) == true
        }
    }

    private fun applyView(p: Pane) {
        p.entries = viewOf(p)
        p.fileAdapter.notifyDataSetChanged()
        updateChips(p)
        p.fileList.requestLayout()
    }

    private fun showSortDialog(p: Pane) {
        val values = SortBy.values()
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(values.map { getString(it.label) }.toTypedArray(), values.indexOf(p.sortBy)) { d, which ->
                p.sortBy = values[which]
                applyView(p)
                d.dismiss()
            }
            .setNeutralButton(if (p.sortDesc) R.string.sort_asc else R.string.sort_desc) { _, _ ->
                p.sortDesc = !p.sortDesc
                applyView(p)
            }
            .show()
    }

    private fun showFilterDialog(p: Pane) {
        val values = Filter.values()
        AlertDialog.Builder(this)
            .setTitle(R.string.filter_title)
            .setSingleChoiceItems(values.map { getString(it.label) }.toTypedArray(), values.indexOf(p.filter)) { d, which ->
                p.filter = values[which]
                applyView(p)
                d.dismiss()
            }
            .setNeutralButton(R.string.filter_name) { _, _ -> showNameDialog(p) }
            .show()
    }

    private fun showNameDialog(p: Pane) {
        val input = EditText(this)
        input.setText(p.nameQuery)
        input.setHint(R.string.filter_name_hint)
        input.setSingleLine()
        AlertDialog.Builder(this)
            .setTitle(R.string.filter_name)
            .setView(input)
            .setPositiveButton(R.string.done) { _, _ ->
                p.nameQuery = input.text.toString().trim()
                applyView(p)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Tree rows for the given expanded folders. Runs on the loader thread. */
    private fun buildNodes(expanded: Set<String>): List<Node> {
        val out = ArrayList<Node>()
        val paint = textPaint(15f)
        fun add(f: File, depth: Int, label: String) {
            out.add(Node(f, depth, label, paint.measureText(label)))
            if (expanded.contains(f.path)) {
                val subs = f.listFiles { x -> x.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }
                for (s in subs) add(s, depth + 1, s.name)
            }
        }
        for (r in roots) add(r, 0, rootNames[r.path] ?: r.name)
        return out
    }

    /** File list of [dir]: one attribute call per entry, sorted once (folders first). Runs on the loader thread. */
    private fun buildEntries(dir: File, showUp: Boolean): List<Entry> {
        val out = ArrayList<Entry>()
        val df = DateFormat.getDateFormat(this)
        val namePaint = textPaint(15f)
        val metaPaint = textPaint(12f)
        fun finish(e: Entry): Entry {
            if (e.up) {
                e.width = namePaint.measureText(getString(R.string.up))
            } else {
                e.meta = if (e.isDir) getString(R.string.items_count, 9999)
                else Formatter.formatShortFileSize(this, e.size) + " · " + df.format(Date(e.modified))
                e.width = maxOf(namePaint.measureText(e.file.name), metaPaint.measureText(e.meta)) + dp(20)
            }
            return e
        }
        if (showUp) dir.parentFile?.let { out.add(finish(Entry(it, true, isDir = true))) }
        val files = dir.listFiles().orEmpty().map { f ->
            try {
                val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
                Entry(f, false, a.isDirectory, a.size(), a.lastModifiedTime().toMillis())
            } catch (e: IOException) {
                Entry(f, false)
            }
        }
        out.addAll(files.map { finish(it) }.sortedWith(compareBy({ !it.isDir }, { it.key })))
        return out
    }

    private fun textPaint(sp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp * resources.displayMetrics.scaledDensity
    }

    private fun label(p: Pane) = getString(p.bandRes) + " · " + p.dir.path

    /** Re-reads one pane in the background; a newer load of the same pane wins. */
    private fun loadPane(p: Pane) {
        val gen = ++p.gen
        val dir = p.dir
        val expanded = HashSet(p.expanded)
        p.treeBand.text = label(p)
        p.fileBand.text = label(p)
        loader.execute {
            val nodes = buildNodes(expanded)
            val showUp = roots.none { it.path == dir.path } && dir.parentFile?.canRead() == true
            val entries = buildEntries(dir, showUp)
            runOnUiThread {
                if (gen != p.gen || isFinishing) return@runOnUiThread
                p.nodes = nodes
                p.raw = entries
                p.entries = viewOf(p)
                updateChips(p)
                p.treeList.contentWidth = nodes.maxOfOrNull { it.width + dp(40) } ?: 0f
                p.fileList.contentWidth = entries.maxOfOrNull { it.width } ?: 0f
                p.treeAdapter.notifyDataSetChanged()
                p.fileAdapter.notifyDataSetChanged()
                p.treeList.requestLayout()
                p.fileList.requestLayout()
            }
        }
    }

    /** Re-reads both panes (resume, after a file operation). */
    private fun refreshAll() {
        for (p in panes) loadPane(p)
        updateBar()
    }

    /** Marks changed: only rows and bar are redrawn, nothing is read again. */
    private fun refreshSelection() {
        for (p in panes) {
            p.treeAdapter.notifyDataSetChanged()
            p.fileAdapter.notifyDataSetChanged()
        }
        updateBar()
    }

    private fun updateBar() {
        val active = panes.firstOrNull { it.selected.isNotEmpty() }
        bar.visibility = if (active == null) View.GONE else View.VISIBLE
        barCount.text = getString(R.string.selected_count, active?.selected?.size ?: 0)
    }

    /** Item count of a folder: null while it is being counted on the counter thread. */
    private fun childCount(path: String): Int? {
        counts[path]?.let { return it }
        if (countsPending.add(path)) {
            counter.execute {
                val n = File(path).list()?.size ?: 0
                runOnUiThread {
                    countsPending.remove(path)
                    if (!isFinishing) {
                        counts[path] = n
                        for (p in panes) p.fileAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
        return null
    }

    // ---- Actions ----

    /** Menu for the held [file]: "Open with" (files only), then copy / move / delete for the marked items. */
    private fun showMenu(file: File) {
        val labels = ArrayList<String>()
        val actions = ArrayList<Int>()
        if (file.isFile) {
            labels.add(getString(R.string.open_with))
            actions.add(3)
        }
        labels.add(getString(R.string.copy_to_target))
        actions.add(0)
        labels.add(getString(R.string.move_to_target))
        actions.add(1)
        labels.add(getString(R.string.delete))
        actions.add(2)
        val n = panes.firstOrNull { it.selected.isNotEmpty() }?.selected?.size ?: 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.selected_count, n))
            .setItems(labels.toTypedArray()) { _, which ->
                if (actions[which] == 3) openWith(file) else act(actions[which])
            }
            .show()
    }

    /** [which]: 0 copy, 1 move, 2 delete; source is the pane with a selection, target the other one. */
    private fun act(which: Int) {
        val src = panes.firstOrNull { it.selected.isNotEmpty() } ?: return
        val dstDir = (if (src === panes[0]) panes[1] else panes[0]).dir
        val items = src.selected.toList()
        when (which) {
            0 -> execute(items) { FileOps.copy(it, dstDir) }
            1 -> execute(items) { FileOps.move(it, dstDir) }
            else -> AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_confirm, items.size))
                .setPositiveButton(R.string.delete) { _, _ -> execute(items) { FileOps.delete(it) } }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun execute(items: List<File>, op: (File) -> Boolean) {
        Thread {
            var failed = 0
            for (f in items) if (!op(f)) failed++
            runOnUiThread {
                for (p in panes) p.selected.clear()
                counts.clear()
                refreshAll()
                val msg = if (failed == 0) getString(R.string.done) else getString(R.string.done_failed, failed)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun isChooser(ri: ResolveInfo): Boolean {
        val ai = ri.activityInfo
        return ai.packageName == "android" || ai.packageName == "com.android.intentresolver" ||
            ai.name.contains("Resolver") || ai.name.contains("Chooser")
    }

    /** The app Android opens this file with by default ("Immer"), or null if none is set. */
    private fun defaultApp(f: File): ResolveInfo? =
        packageManager.resolveActivity(viewIntent(f), 0)?.takeUnless { isChooser(it) }

    private fun viewIntent(f: File): Intent {
        val uri = Uri.Builder().scheme("content").authority("$packageName.files").path(f.absolutePath).build()
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, FileOps.mime(f) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun start(i: Intent) {
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** Android's app chooser for "Öffnen mit…". */
    private fun chooser(f: File): Intent = Intent.createChooser(viewIntent(f), getString(R.string.open_with))

    /** Tap: the default app opens directly; without a default, Android's chooser asks. */
    private fun openFile(f: File) = start(if (defaultApp(f) != null) viewIntent(f) else chooser(f))

    private fun openWith(f: File) = start(chooser(f))

    // ---- Adapters ----

    private inner class TreeAdapter(val p: Pane) : BaseAdapter() {
        override fun getCount() = p.nodes.size
        override fun getItem(position: Int): Any = p.nodes[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? TreeRow ?: TreeRow(this@MainActivity)
            val n = p.nodes[position]
            val current = n.file.path == p.dir.path
            val expanded = p.expanded.contains(n.file.path)
            row.setPadding(dp(8 + n.depth * 20), 0, 0, 0)
            row.setBackgroundColor(if (current) getColor(R.color.md_container) else Color.TRANSPARENT)
            row.chevron.text = if (expanded) "▾" else "▸"
            row.chevron.setTextColor(getColor(R.color.md_on_surface_variant))
            row.chevron.setOnClickListener {
                if (!p.expanded.remove(n.file.path)) p.expanded.add(n.file.path)
                loadPane(p)
            }
            row.icon.setImageDrawable(EntryIcon("", true, p.color, Color.WHITE, false, p.color))
            row.label.text = n.label
            row.label.setTextColor(getColor(R.color.md_on_surface))
            row.label.typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            p.treeList.applyTo(row)
            return row
        }
    }

    private inner class FileAdapter(val p: Pane) : BaseAdapter() {
        override fun getCount() = p.entries.size
        override fun getItem(position: Int): Any = p.entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? FileRow ?: FileRow(this@MainActivity)
            val e = p.entries[position]
            val f = e.file
            val sel = !e.up && p.selected.contains(f)
            val isDir = e.up || e.isDir
            row.setBackgroundColor(if (sel) tint(p.color) else Color.TRANSPARENT)

            // Icon: filled folder, or the file's extension on a colour from its hue; check badge when marked.
            val ext = if (isDir) "" else f.extension.lowercase().take(4)
            val (fill, text) = if (isDir) Pair(p.color, Color.WHITE) else VividColors.colorsFor(f.extension.lowercase())
            row.icon.setImageDrawable(EntryIcon(ext.uppercase(), isDir, fill, text, sel, p.color))
            row.iconHit.visibility = if (e.up) View.INVISIBLE else View.VISIBLE
            row.iconHit.setOnClickListener { toggle(p, f) }

            row.name.text = if (e.up) getString(R.string.up) else f.name
            row.name.setTextColor(getColor(R.color.md_on_surface))
            row.meta.setTextColor(getColor(R.color.md_on_surface_variant))
            row.meta.text = when {
                e.up -> ""
                e.isDir -> childCount(f.path)?.let { getString(R.string.items_count, it) } ?: "…"
                else -> e.meta
            }
            p.fileList.applyTo(row)
            return row
        }
    }

    private fun tint(c: Int) = Color.argb(0x40, Color.red(c), Color.green(c), Color.blue(c))
}
