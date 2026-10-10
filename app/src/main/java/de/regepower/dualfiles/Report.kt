package de.regepower.dualfiles

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.DateFormat
import java.util.Date

/**
 * Error report for the developer: the last crash (written by our own handler) and the last exits the
 * system recorded (crash, "app not responding" with its thread dump). Nothing is sent anywhere; the
 * report is opened in the viewer, where it can be copied or shared.
 */
internal object Report {
    private const val CRASH_FILE = "last_crash.txt"

    /** Keeps the stack trace of an uncaught exception, then lets Android handle the crash as usual. */
    fun install(ctx: Context) {
        val file = File(ctx.filesDir, CRASH_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file.writeText("${Date()} thread ${t.name}\n$sw")
            } catch (ignored: Exception) {
                // the crash itself matters more
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Writes the report to the cache and returns it. */
    fun build(ctx: Context): File {
        val sb = StringBuilder()
        val df = DateFormat.getDateTimeInstance()
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        sb.append("DualFiles ").append(version).append(" · Android ").append(android.os.Build.VERSION.RELEASE)
            .append(" · ").append(android.os.Build.MODEL).append("\n\n")
        val crash = File(ctx.filesDir, CRASH_FILE)
        sb.append("== Last crash ==\n").append(if (crash.isFile) crash.readText() else "none\n").append('\n')
        val am = ctx.getSystemService(ActivityManager::class.java)
        val exits = am.getHistoricalProcessExitReasons(null, 0, 5)
        for (e in exits) {
            sb.append("== Exit ").append(df.format(Date(e.timestamp))).append(": ").append(reason(e.reason))
                .append(" (").append(e.description ?: "").append(") ==\n")
            if (e.reason == ApplicationExitInfo.REASON_ANR) {
                // The thread dump; the main thread comes first and tells where the app hung
                val trace = try {
                    e.traceInputStream?.bufferedReader()?.use { r -> r.lineSequence().take(400).joinToString("\n") }
                } catch (ex: Exception) {
                    null
                }
                sb.append(trace ?: "(no trace)").append('\n')
            }
            sb.append('\n')
        }
        val out = File(ctx.cacheDir, "DualFiles-report.txt")
        out.writeText(sb.toString())
        return out
    }

    private fun reason(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_EXIT_SELF -> "closed"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by user"
        ApplicationExitInfo.REASON_SIGNALED -> "killed"
        else -> "other ($r)"
    }
}
