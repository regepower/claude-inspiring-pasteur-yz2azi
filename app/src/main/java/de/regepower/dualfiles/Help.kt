package de.regepower.dualfiles

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.util.Linkify
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Help dialog: foldable chapters, then version, build day, donation line and FOSS note. */
object Help {
    @Suppress("DEPRECATION")
    fun show(a: Activity) {
        val dp = a.resources.displayMetrics.density
        val titles = a.resources.getStringArray(R.array.help_titles)
        val bodies = a.resources.getStringArray(R.array.help_bodies)
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())

        for (i in titles.indices) {
            val head = TextView(a)
            head.textSize = 16f
            head.typeface = android.graphics.Typeface.DEFAULT_BOLD
            head.setTextColor(a.getColor(R.color.md_on_container))
            head.setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            val body = TextView(a)
            body.text = bodies[i]
            body.textSize = 14f
            body.setTextColor(a.getColor(R.color.md_on_surface))
            body.setPadding(0, 0, 0, (8 * dp).toInt())
            body.visibility = if (i == 0) View.VISIBLE else View.GONE
            // Only the first chapter starts open.
            fun sync() {
                val open = body.visibility == View.VISIBLE
                head.text = a.getString(if (open) R.string.help_open else R.string.help_closed, titles[i])
                head.stateDescription = a.getString(if (open) R.string.help_expanded else R.string.help_collapsed)
            }
            head.setOnClickListener {
                body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                sync()
            }
            sync()
            col.addView(head)
            col.addView(body)
        }

        val info = a.packageManager.getPackageInfo(a.packageName, 0)
        val meta = a.packageManager.getApplicationInfo(a.packageName, PackageManager.GET_META_DATA).metaData
        val day = meta?.getString("build_date")?.let {
            LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        } ?: ""
        val footer = TextView(a)
        footer.textSize = 12f
        footer.setTextColor(a.getColor(R.color.md_on_surface_variant))
        footer.setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        footer.autoLinkMask = Linkify.WEB_URLS // before setText, so the URL becomes tappable
        footer.text = a.getString(
            R.string.help_footer,
            a.getString(R.string.app_name),
            info.versionName,
            day,
            a.getString(R.string.donate_text),
            a.getString(R.string.foss_text, a.getString(R.string.source_url))
        )
        col.addView(footer)

        val scroll = ScrollView(a)
        scroll.addView(col)
        AlertDialog.Builder(a)
            .setTitle(R.string.help)
            .setView(scroll)
            .setPositiveButton(R.string.help_ok, null)
            .setNeutralButton(R.string.donate) { _, _ ->
                val url = a.getString(R.string.donate_url)
                try {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(a, url, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }
}
