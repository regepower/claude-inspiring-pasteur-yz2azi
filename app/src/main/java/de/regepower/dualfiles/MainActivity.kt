package de.regepower.dualfiles

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Date

private const val SRC_COLOR = 0xFF1F5FBF.toInt()
private const val DST_COLOR = 0xFFB45309.toInt()
private const val ERROR_COLOR = 0xFFB3261E.toInt()

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** One row of the folder tree. */
private class Node(val file: File, val depth: Int, val label: String)

/** One row of the file list; [up] marks the ".." row, whose [file] is the parent folder. */
private class Entry(val file: File, val up: Boolean)

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
    var treePage = 0
    var filePage = 0
}

private class TreeRow(ctx: Context) : LinearLayout(ctx) {
    val chevron = TextView(ctx)
    val icon = ImageView(ctx)
    val label = TextView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = rowParams(ctx.dp(48))
        chevron.gravity = Gravity.CENTER
        chevron.textSize = 14f
        addView(chevron, LayoutParams(ctx.dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
        icon.setImageResource(R.drawable.ic_folder)
        addView(icon, LayoutParams(ctx.dp(24), ctx.dp(24)))
        label.textSize = 15f
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.setPadding(ctx.dp(10), 0, ctx.dp(12), 0)
        addView(label, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}

/** Horizontal scroller that never reacts to touch; the hold-and-drag gesture of [HoldList] moves it. */
private class NoTouchScroll(ctx: Context) : HorizontalScrollView(ctx) {
    init {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(ctx.dp(24))
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean = false
}

private class FileRow(ctx: Context) : LinearLayout(ctx) {
    val box = TextView(ctx)
    val icon = ImageView(ctx)
    val names = NoTouchScroll(ctx)
    val inner = LinearLayout(ctx)
    val name = TextView(ctx)
    val meta = TextView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = rowParams(ctx.dp(56))
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        box.gravity = Gravity.CENTER
        box.textSize = 13f
        box.setTextColor(Color.WHITE)
        addView(box, LayoutParams(ctx.dp(22), ctx.dp(22)))
        addView(icon, LayoutParams(ctx.dp(44), ctx.dp(44)).also { it.leftMargin = ctx.dp(6) })
        icon.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        inner.orientation = VERTICAL
        name.textSize = 15f
        name.maxLines = 1
        meta.textSize = 12f
        meta.maxLines = 1
        inner.addView(name)
        inner.addView(meta)
        names.addView(inner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(names, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
    private val iconCache = HashMap<String, Drawable?>()
    private var lastTapFile: File? = null
    private var lastTapTime = 0L

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

        val root = android.widget.FrameLayout(this)
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

    private fun styledList(l: ListView = ListView(this)): ListView {
        l.divider = ColorDrawable(getColor(R.color.md_outline) and 0x55FFFFFF)
        l.dividerHeight = 1
        return l
    }

    private fun buildTree(p: Pane): View {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        p.treeBand = band(p)
        page.addView(p.treeBand)
        val list = styledList()
        p.treeAdapter = TreeAdapter(p)
        list.adapter = p.treeAdapter
        list.setOnItemClickListener { _, _, pos, _ ->
            open(p, p.nodes[pos].file)
            pager.snapTo(p.filePage)
        }
        page.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return page
    }

    private fun buildFiles(p: Pane): View {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        p.fileBand = band(p)
        page.addView(p.fileBand)
        val list = HoldList(this)
        styledList(list)
        p.fileAdapter = FileAdapter(p)
        list.adapter = p.fileAdapter
        // Tap marks; a second tap on the same entry within the double-tap time undoes the mark and opens it.
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = p.entries[pos]
            if (e.up) {
                open(p, e.file)
            } else {
                val now = SystemClock.uptimeMillis()
                val double = e.file == lastTapFile && now - lastTapTime < ViewConfiguration.getDoubleTapTimeout()
                toggle(p, e.file)
                if (double) {
                    lastTapFile = null
                    if (e.file.isDirectory) open(p, e.file) else openFile(e.file)
                } else {
                    lastTapFile = e.file
                    lastTapTime = now
                }
            }
        }
        // Hold + drag sideways scrolls the name; hold + release without moving opens the menu.
        list.canHold = { it < p.entries.size && !p.entries[it].up }
        list.onHoldMove = { pos, dx ->
            val r = list.getChildAt(pos - list.firstVisiblePosition) as? FileRow
            if (r != null) {
                val max = (r.inner.width - r.names.width).coerceAtLeast(0)
                r.names.scrollTo((-dx).toInt().coerceIn(0, max), 0)
            }
        }
        list.onHoldEnd = { pos, moved ->
            (list.getChildAt(pos - list.firstVisiblePosition) as? FileRow)?.names?.scrollTo(0, 0)
            if (!moved && pos < p.entries.size) {
                val e = p.entries[pos]
                if (!p.selected.contains(e.file)) toggle(p, e.file)
                showMenu()
            }
        }
        page.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
        refreshAll()
    }

    private fun toggle(p: Pane, f: File) {
        if (!p.selected.remove(f)) p.selected.add(f)
        for (other in panes) if (other !== p) other.selected.clear()
        refreshAll()
    }

    private fun buildNodes(p: Pane): List<Node> {
        val out = ArrayList<Node>()
        fun add(f: File, depth: Int, label: String) {
            out.add(Node(f, depth, label))
            if (p.expanded.contains(f.path)) {
                val subs = f.listFiles { x -> x.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }
                for (s in subs) add(s, depth + 1, s.name)
            }
        }
        for (r in roots) add(r, 0, rootNames[r.path] ?: r.name)
        return out
    }

    private fun buildEntries(p: Pane): List<Entry> {
        val out = ArrayList<Entry>()
        val parent = p.dir.parentFile
        if (parent != null && roots.none { it.path == p.dir.path } && parent.canRead()) out.add(Entry(parent, true))
        val sorted = p.dir.listFiles().orEmpty().sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        for (f in sorted) out.add(Entry(f, false))
        return out
    }

    private fun refreshAll() {
        for (p in panes) {
            p.nodes = buildNodes(p)
            p.entries = buildEntries(p)
            val label = getString(p.bandRes) + " · " + p.dir.path
            p.treeBand.text = label
            p.fileBand.text = label
            p.treeAdapter.notifyDataSetChanged()
            p.fileAdapter.notifyDataSetChanged()
        }
        val active = panes.firstOrNull { it.selected.isNotEmpty() }
        bar.visibility = if (active == null) View.GONE else View.VISIBLE
        barCount.text = getString(R.string.selected_count, active?.selected?.size ?: 0)
    }

    // ---- Actions ----

    private fun showMenu() {
        val items = arrayOf(
            getString(R.string.copy_to_target), getString(R.string.move_to_target), getString(R.string.delete)
        )
        val n = panes.firstOrNull { it.selected.isNotEmpty() }?.selected?.size ?: 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.selected_count, n))
            .setItems(items) { _, which -> act(which) }
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
                refreshAll()
                val msg = if (failed == 0) getString(R.string.done) else getString(R.string.done_failed, failed)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Icon of the app that opens this file type (like Windows), or null. Cached per extension. */
    @Suppress("DEPRECATION")
    private fun appIcon(f: File): Drawable? {
        val ext = f.extension.lowercase()
        if (ext.isEmpty()) return null
        if (!iconCache.containsKey(ext)) {
            var icon: Drawable? = null
            val mime = FileOps.mime(f)
            if (mime != null) {
                val pm = packageManager
                val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://x/f.$ext"), mime)
                var ri = pm.resolveActivity(probe, 0)
                // "android" = system chooser (no default app): take the first real handler instead.
                if (ri == null || ri.activityInfo.packageName == "android") {
                    ri = pm.queryIntentActivities(probe, 0).firstOrNull { it.activityInfo.packageName != packageName }
                }
                icon = ri?.loadIcon(pm)
            }
            iconCache[ext] = icon
        }
        val d = iconCache[ext] ?: return null
        return d.constantState?.newDrawable() ?: d
    }

    private fun openFile(f: File) {
        val uri = Uri.Builder().scheme("content").authority("$packageName.files").path(f.absolutePath).build()
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, FileOps.mime(f) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

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
                p.nodes = buildNodes(p)
                notifyDataSetChanged()
            }
            row.icon.imageTintList = android.content.res.ColorStateList.valueOf(p.color)
            row.label.text = n.label
            row.label.setTextColor(getColor(R.color.md_on_surface))
            row.label.typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
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
            row.setBackgroundColor(if (sel) getColor(R.color.md_container) else Color.TRANSPARENT)

            val box = GradientDrawable()
            box.cornerRadius = dp(5).toFloat()
            box.setStroke(dp(2), if (sel) p.color else getColor(R.color.md_outline))
            if (sel) box.setColor(p.color)
            row.box.background = box
            row.box.text = if (sel) "✓" else ""
            row.box.visibility = if (e.up) View.INVISIBLE else View.VISIBLE

            val isDir = e.up || f.isDirectory
            val appDrawable = if (isDir) null else appIcon(f)
            if (appDrawable != null) {
                row.icon.setImageDrawable(appDrawable)
                row.icon.imageTintList = null
            } else {
                row.icon.setImageResource(if (isDir) R.drawable.ic_folder else R.drawable.ic_file)
                row.icon.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (isDir) p.color else getColor(R.color.md_on_surface_variant)
                )
            }
            row.names.scrollTo(0, 0)

            row.name.text = if (e.up) getString(R.string.up) else f.name
            row.name.setTextColor(getColor(R.color.md_on_surface))
            row.meta.setTextColor(getColor(R.color.md_on_surface_variant))
            row.meta.text = when {
                e.up -> ""
                f.isDirectory -> getString(R.string.items_count, f.list()?.size ?: 0)
                else -> Formatter.formatShortFileSize(this@MainActivity, f.length()) + " · " +
                    DateFormat.getDateFormat(this@MainActivity).format(Date(f.lastModified()))
            }
            return row
        }
    }
}
