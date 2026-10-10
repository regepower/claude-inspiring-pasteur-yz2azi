package de.regepower.dualfiles

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Preview pictures of images and videos for the file list. The folder opens at once with the normal
 * icons; previews are made in the background only for rows that are shown and replace the icons as they
 * come. Speed: the system's own cached previews (MediaStore) are used where they exist, several cores
 * work in parallel, the newest request goes first (the rows you look at) and requests for rows that
 * scrolled away are dropped. Kept in memory (about 1/8 of the app's heap), keyed by path and date.
 */
internal object Thumbs {
    private const val SIZE = 160   // px, enough for the 38 x 44 dp icon on dense screens
    private const val STALE_MS = 1500L

    // Last in, first out: the rows on screen now are made before rows that were passed while scrolling
    private val queue = object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable) = super.offerFirst(e)
    }
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val worker = ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, queue).apply { allowCoreThreadTimeOut(true) }
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Long>()   // key -> last time a row asked (read by the workers)
    private val failed = HashSet<String>()          // UI thread only
    private val mediaIds = HashMap<String, Map<String, Pair<Long, Boolean>>>()   // folder -> path -> (id, video); guarded by itself
    private var redraw: (() -> Unit)? = null        // UI thread only

    /** True for files that can have a preview. */
    fun canPreview(f: File): Boolean {
        val mime = FileOps.mime(f) ?: return false
        return mime.startsWith("image/") || mime.startsWith("video/")
    }

    /**
     * The preview of [f] if it is ready; otherwise null, and it is made in the background and
     * [onReady] runs on the UI thread when previews arrived (at most every 100 ms).
     */
    fun get(ctx: Context, f: File, modified: Long, onReady: () -> Unit): Bitmap? {
        val key = "${f.path}|$modified"
        cache.get(key)?.let { return it }
        if (key in failed) return null
        val now = SystemClock.uptimeMillis()
        val known = pending.containsKey(key)
        pending[key] = now
        if (known) return null
        val app = ctx.applicationContext
        worker.execute {
            // Skip rows that are no longer asked for (scrolled past); they are requested again when shown
            val asked = pending[key] ?: 0L
            val bmp = if (SystemClock.uptimeMillis() - asked > STALE_MS) null else try {
                make(app, f)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (bmp == null && SystemClock.uptimeMillis() - (pending[key] ?: 0L) > STALE_MS) {
                    pending.remove(key)   // dropped as stale: may be asked again
                    return@post
                }
                pending.remove(key)
                if (bmp == null) failed.add(key) else cache.put(key, bmp)
                if (bmp != null) {
                    // One redraw for several previews that arrive together
                    if (redraw == null) main.postDelayed({ redraw?.invoke(); redraw = null }, 100)
                    redraw = onReady
                }
            }
        }
        return null
    }

    fun clear() {
        cache.evictAll()
        failed.clear()
        synchronized(mediaIds) { mediaIds.clear() }
    }

    private fun make(ctx: Context, f: File): Bitmap? {
        val size = Size(SIZE, SIZE)
        if (Net.isNet(f)) return null   // no previews over the network: each would download the whole file
        if (!Vfs.isVirtual(f)) mediaThumb(ctx, f, size)?.let { return it }
        if (Saf.isSaf(f)) {
            val uri = Saf.docUri(ctx, f) ?: return null
            return ctx.contentResolver.loadThumbnail(uri, size, null)
        }
        val mime = FileOps.mime(f) ?: return null
        return if (mime.startsWith("video/")) ThumbnailUtils.createVideoThumbnail(f, size, null)
        else ThumbnailUtils.createImageThumbnail(f, size, null)
    }

    /** The system's cached preview of a local picture or video, found by path (one query per folder). */
    private fun mediaThumb(ctx: Context, f: File, size: Size): Bitmap? {
        val dir = f.parent ?: return null
        val ids = synchronized(mediaIds) { mediaIds[dir] } ?: run {
            val map = HashMap<String, Pair<Long, Boolean>>()
            try {
                @Suppress("DEPRECATION")
                val data = MediaStore.MediaColumns.DATA
                ctx.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    arrayOf(MediaStore.MediaColumns._ID, data, MediaStore.Files.FileColumns.MEDIA_TYPE),
                    "$data LIKE ? AND $data NOT LIKE ?", arrayOf("$dir/%", "$dir/%/%"), null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val type = c.getInt(2)
                        if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE || type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                            map[c.getString(1)] = Pair(c.getLong(0), type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                        }
                    }
                }
            } catch (e: Exception) {
                // no media index: decode the file instead
            }
            synchronized(mediaIds) { mediaIds[dir] = map }
            map
        }
        val (id, video) = ids[f.path] ?: return null
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return try {
            ctx.contentResolver.loadThumbnail(ContentUris.withAppendedId(base, id), size, null)
        } catch (e: Exception) {
            null
        }
    }
}
