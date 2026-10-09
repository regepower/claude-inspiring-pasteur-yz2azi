package de.regepower.dualfiles

import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import java.io.File
import java.util.concurrent.Executors

/**
 * Preview pictures of images and videos for the file list. They are made on a background thread only
 * when a row is shown, so reading a folder stays as fast as without them; the row redraws when its
 * picture is ready. Kept in memory (about 1/8 of the app's heap), keyed by path and modification time.
 */
internal object Thumbs {
    private const val SIZE = 160   // px, enough for the 38 x 44 dp icon on dense screens

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val pending = HashSet<String>()   // UI thread only
    private val failed = HashSet<String>()    // UI thread only

    /** True for files that can have a preview. */
    fun canPreview(f: File): Boolean {
        val mime = FileOps.mime(f) ?: return false
        return mime.startsWith("image/") || mime.startsWith("video/")
    }

    /**
     * The preview of [f] if it is ready; otherwise null, and it is made in the background
     * and [onReady] runs on the UI thread when it is there.
     */
    fun get(ctx: Context, f: File, modified: Long, onReady: () -> Unit): Bitmap? {
        val key = "${f.path}|$modified"
        cache.get(key)?.let { return it }
        if (key in failed || !pending.add(key)) return null
        val app = ctx.applicationContext
        worker.execute {
            val bmp = try {
                make(app, f)
            } catch (e: Exception) {
                null
            }
            main.post {
                pending.remove(key)
                if (bmp == null) failed.add(key) else cache.put(key, bmp)
                if (bmp != null) onReady()
            }
        }
        return null
    }

    fun clear() {
        cache.evictAll()
        failed.clear()
    }

    private fun make(ctx: Context, f: File): Bitmap? {
        val size = Size(SIZE, SIZE)
        if (Saf.isSaf(f)) {
            val uri = Saf.docUri(ctx, f) ?: return null
            return ctx.contentResolver.loadThumbnail(uri, size, null)
        }
        val mime = FileOps.mime(f) ?: return null
        return if (mime.startsWith("video/")) ThumbnailUtils.createVideoThumbnail(f, size, null)
        else ThumbnailUtils.createImageThumbnail(f, size, null)
    }
}
