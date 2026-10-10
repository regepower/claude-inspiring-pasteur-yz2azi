package de.regepower.dualfiles

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Progress of a pack/unpack run; returning false cancels it. Called from the worker thread (and from C). */
fun interface ArcProgress {
    fun step(done: Long, total: Long): Boolean
}

/** Native helpers (src/main/cpp): fast folder listing and EXIF for WebP. [ok] is false if the library is missing. */
internal object NativeLib {
    val ok = try {
        System.loadLibrary("dualfiles")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    /** { String[] names, long[] info } with size, modified ms and flags (1 folder, 2 read) per entry; null on error. */
    @JvmStatic external fun listDir(path: String): Array<Any>?

    /** EXIF (TIFF block) of a JPEG with the orientation set to normal, or null. */
    @JvmStatic external fun jpegExif(path: String): ByteArray?

    /** Adds [exif] to the WebP file [path] (rewritten in the extended format). */
    @JvmStatic external fun webpAddExif(path: String, exif: ByteArray, w: Int, h: Int, alpha: Boolean): Boolean
}

/** 7z reading in C (7z decoder of the LZMA SDK, see src/main/cpp). */
internal object SevenZip {
    init {
        System.loadLibrary("dualfiles")
    }

    /** One "D|F \t size \t mtime \t name" string per entry, or a single "!code" on failure. */
    @JvmStatic external fun list(archive: String): Array<String?>

    /** Writes every entry whose [outNames] element is set to [outDir]/name: number of entries, or -code. */
    @JvmStatic external fun extract(archive: String, outDir: String, outNames: Array<String?>, progress: ArcProgress): Int
}

/**
 * ZIP and 7z archives as read-only folders: a path below an archive file ("/x/a.zip/docs/b.txt") is a
 * virtual path into it, like the "/saf/" paths. Unpacking all or a selection, packing ZIP.
 * Archives in Saf folders are not opened as folders, but can be unpacked as a whole (via a cache copy).
 */
internal object Archive {
    /** Error text resource for a failed run; null means success. */
    class Result(val error: Int?, val failed: Int = 0)

    /** One entry; [index] is its position in the archive, -1 for a folder that only exists implicitly. */
    class Item(val index: Int, val path: String, val isDir: Boolean, val size: Long, val modified: Long) {
        val name: String get() = path.substringAfterLast('/')
    }

    private class Listing(val stamp: Long, val count: Int, val items: List<Item>, val error: Int?)

    private val EXTS = setOf("zip", "7z")
    private val cache = HashMap<String, Listing>()   // archive path -> contents; guarded by itself

    fun isArchiveName(f: File) = f.extension.lowercase() in EXTS

    /** A local archive file that can be opened like a folder. */
    fun isArchive(f: File) = !Saf.isSaf(f) && isArchiveName(f) && f.isFile

    /** For a path inside an archive (or the archive itself): the archive file and the inner path ("" = top). */
    fun split(f: File): Pair<File, String>? {
        if (Saf.isSaf(f)) return null
        var cur: File? = f
        while (cur != null) {
            if (isArchiveName(cur) && cur.isFile) {
                val inner = f.path.removePrefix(cur.path).trimStart('/')
                return Pair(cur, inner)
            }
            // A real folder above: nothing virtual here
            if (cur.isDirectory) return null
            cur = cur.parentFile
        }
        return null
    }

    fun inside(f: File) = split(f) != null

    /** Entries directly in [inner] of [archive] (folders implied by deeper paths included). */
    fun children(archive: File, inner: String): List<Item> {
        val all = listing(archive).items
        val prefix = if (inner.isEmpty()) "" else "$inner/"
        return all.filter { it.path.startsWith(prefix) && it.path.length > prefix.length && it.path.indexOf('/', prefix.length) < 0 }
    }

    /** Error of reading [archive], or null. */
    fun error(archive: File): Int? = listing(archive).error

    private fun listing(archive: File): Listing {
        val stamp = archive.lastModified() xor archive.length()
        synchronized(cache) { cache[archive.path]?.takeIf { it.stamp == stamp }?.let { return it } }
        val l = try {
            if (archive.extension.lowercase() == "7z") list7z(archive, stamp) else listZip(archive, stamp)
        } catch (e: Exception) {
            Listing(stamp, 0, emptyList(), R.string.arc_damaged)
        }
        synchronized(cache) { cache[archive.path] = l }
        return l
    }

    private fun list7z(archive: File, stamp: Long): Listing {
        val rows = try {
            SevenZip.list(archive.path)
        } catch (e: UnsatisfiedLinkError) {
            return Listing(stamp, 0, emptyList(), R.string.arc_unsupported)
        }
        rows.firstOrNull()?.takeIf { it.startsWith("!") }?.let {
            return Listing(stamp, 0, emptyList(), errorText(-(it.drop(1).toIntOrNull() ?: 1)))
        }
        val items = rows.mapIndexedNotNull { i, row ->
            val parts = row?.split('\t', limit = 4)?.takeIf { it.size == 4 } ?: return@mapIndexedNotNull null
            val path = clean(parts[3]) ?: return@mapIndexedNotNull null
            Item(i, path, parts[0] == "D", parts[1].toLongOrNull() ?: 0, parts[2].toLongOrNull() ?: 0)
        }
        return Listing(stamp, rows.size, withFolders(items), null)
    }

    private fun listZip(archive: File, stamp: Long): Listing {
        val entries = openZip(archive).use { z -> z.entries().toList() }
        val items = entries.mapIndexedNotNull { i, e ->
            val path = clean(e.name) ?: return@mapIndexedNotNull null
            Item(i, path, e.isDirectory, if (e.isDirectory) 0 else maxOf(e.size, 0), maxOf(e.time, 0))
        }
        return Listing(stamp, entries.size, withFolders(items), null)
    }

    /** ZIP with UTF-8 names; older Windows archives (CP437 names) on a second try. */
    private fun openZip(archive: File, cs: Charset = Charsets.UTF_8): ZipFile = try {
        ZipFile(archive, cs).also { z -> z.entries().toList() }   // name decoding fails here, not later
    } catch (e: IllegalArgumentException) {
        if (cs == Charsets.UTF_8) openZip(archive, Charset.forName("IBM437")) else throw e
    }

    /** Normalised inner path: "/" separators, no empty, "." or ".." parts (unsafe entries are hidden). */
    private fun clean(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.contains("..")) return null
        return parts.joinToString("/")
    }

    /** Adds folders that only exist as part of deeper paths. */
    private fun withFolders(items: List<Item>): List<Item> {
        val known = items.filter { it.isDir }.map { it.path }.toHashSet()
        val extra = ArrayList<Item>()
        for (it in items) {
            var p = it.path.substringBeforeLast('/', "")
            while (p.isNotEmpty() && known.add(p)) {
                extra.add(Item(-1, p, true, 0, 0))
                p = p.substringBeforeLast('/', "")
            }
        }
        return items + extra
    }

    fun errorText(code: Int): Int = when (code) {
        -2 -> R.string.arc_memory          // SZ_ERROR_MEM
        -4 -> R.string.arc_unsupported     // encrypted or unknown method
        -9 -> R.string.arc_write           // SZ_ERROR_WRITE
        -100 -> R.string.arc_bad_path
        -101 -> R.string.arc_cancelled
        -102 -> R.string.arc_read
        else -> R.string.arc_damaged       // data, CRC, not an archive, ...
    }

    /**
     * Unpacks the [selected] inner paths ("" = everything) of [archive] into [dstDir]: each selected entry
     * lands there with its own name (top-level names made unique), inside a new folder [folder] if given.
     * [archive] may lie in a Saf folder (whole archive only), [dstDir] may be a Saf folder.
     */
    fun extract(
        ctx: Context, archive: File, selected: List<String>, dstDir: File, folder: String?,
        clashes: Map<String, Clash>, progress: ArcProgress,
    ): Result {
        val work = File(ctx.cacheDir, "archive").apply { deleteRecursively(); mkdirs() }
        try {
            val local = if (Saf.isSaf(archive)) {
                val copy = File(work, "in." + archive.extension)
                val input = Transfer.openInput(ctx, archive) ?: return Result(R.string.arc_read)
                input.use { i -> copy.outputStream().use { i.copyTo(it) } }
                copy
            } else archive
            val l = listing(local)
            if (Saf.isSaf(archive)) synchronized(cache) { cache.remove(local.path) }
            l.error?.let { return Result(it) }

            // Output name for every wanted entry, relative to the output folder
            val toSaf = Saf.isSaf(dstDir)
            val out = if (toSaf) File(work, "out").apply { mkdirs() } else dstDir
            val tops = HashMap<String, String?>()  // top-level name -> name in out (null = skipped)
            val names = arrayOfNulls<String>(l.count)
            for (item in l.items) {
                if (item.index < 0) continue
                val sel = selected.firstOrNull { it.isEmpty() || item.path == it || item.path.startsWith("$it/") } ?: continue
                val cut = if (sel.isEmpty()) 0 else sel.lastIndexOf('/') + 1
                var rel = item.path.substring(cut)
                if (folder != null) rel = "$folder/$rel"
                val top = rel.substringBefore('/')
                val unique = tops.getOrPut(top) {
                    when (clashes[top]) {
                        Clash.SKIP -> null
                        Clash.OVERWRITE -> top
                        else -> if (toSaf) top else FileOps.uniqueTarget(out, top).name
                    }
                } ?: continue
                names[item.index] = unique + rel.substring(top.length)
            }
            if (names.all { it == null }) return Result(null)

            val error = if (local.extension.lowercase() == "7z") {
                val r = try {
                    SevenZip.extract(local.path, out.path, names, progress)
                } catch (e: UnsatisfiedLinkError) {
                    -4
                }
                if (r < 0) errorText(r) else null
            } else unzip(local, out, names, progress)
            if (error != null) {
                // Remove only what this run created; folders merged into (overwrite) stay
                for ((top, t) in tops) if (t != null && (toSaf || clashes[top] != Clash.OVERWRITE)) File(out, t).deleteRecursively()
                return Result(error)
            }
            if (toSaf) for ((top, t) in tops) {
                if (t != null && !Transfer.copy(ctx, File(out, t), dstDir, clashes[top] == Clash.OVERWRITE)) return Result(R.string.arc_write)
            }
            return Result(null)
        } catch (e: IOException) {
            return Result(R.string.arc_read)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun unzip(archive: File, out: File, names: Array<String?>, progress: ArcProgress): Int? {
        return try {
            val root = out.canonicalPath + File.separator
            openZip(archive).use { zip ->
                val entries = zip.entries().toList()
                val total = entries.withIndex().sumOf { (i, e) -> if (names.getOrNull(i) != null && !e.isDirectory) maxOf(e.size, 0) else 0L }
                var done = 0L
                for ((i, e) in entries.withIndex()) {
                    val name = names.getOrNull(i) ?: continue
                    if (!progress.step(done, total)) return R.string.arc_cancelled
                    val target = File(out, name)
                    // No entry may leave the output folder
                    if (!target.canonicalPath.startsWith(root)) return R.string.arc_bad_path
                    if (e.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(e).use { i ->
                        target.outputStream().use { o ->
                            done = copy(i, o, done, total, progress) ?: return R.string.arc_cancelled
                        }
                    }
                    if (e.time > 0) target.setLastModified(e.time)
                }
                progress.step(done, total)
            }
            null
        } catch (e: ZipException) {
            if (e.message?.contains("encrypt", true) == true) R.string.arc_unsupported else R.string.arc_damaged
        } catch (e: IllegalArgumentException) {
            R.string.arc_damaged
        } catch (e: IOException) {
            R.string.arc_write
        }
    }

    /** Copies with progress; the new done count, or null when cancelled. */
    private fun copy(i: InputStream, o: OutputStream, start: Long, total: Long, progress: ArcProgress): Long? {
        val buf = ByteArray(64 * 1024)
        var done = start
        var last = 0L
        while (true) {
            val n = i.read(buf)
            if (n < 0) return done
            o.write(buf, 0, n)
            done += n
            if (done - last >= 512 * 1024) {
                last = done
                if (!progress.step(done, total)) return null
            }
        }
    }

    /** Extracts one file of an archive to the cache, for opening it in another app. */
    fun extractForView(ctx: Context, archive: File, inner: String, progress: ArcProgress): File? {
        val dir = File(ctx.cacheDir, "view").apply { deleteRecursively(); mkdirs() }
        val r = extract(ctx, archive, listOf(inner), dir, null, emptyMap(), progress)
        return File(dir, inner.substringAfterLast('/')).takeIf { r.error == null && it.isFile }
    }

    /** Packs [items] (files and folders) into [dstDir]/[name]. */
    fun zip(ctx: Context, items: List<File>, dstDir: File, name: String, overwrite: Boolean, progress: ArcProgress): Result {
        if (overwrite) File(dstDir, name).let { if (Transfer.isDirectory(ctx, it) == false) Transfer.delete(ctx, it) }
        val target = Transfer.createChild(ctx, dstDir, name, false) ?: return Result(R.string.arc_write)
        val total = items.sumOf { Transfer.size(ctx, it) }
        var cancelled = false
        val ok = try {
            val output = Transfer.openOutput(ctx, target) ?: throw IOException()
            ZipOutputStream(output.buffered()).use { z ->
                var done = 0L
                for (f in items) {
                    done = add(ctx, z, f, f.name, target, done, total, progress) ?: run {
                        cancelled = true
                        return@use false
                    }
                }
                progress.step(done, total)
                true
            }
        } catch (e: IOException) {
            false
        }
        if (!ok) Transfer.delete(ctx, target)
        return Result(if (ok) null else if (cancelled) R.string.arc_cancelled else R.string.arc_write)
    }

    /** Adds [f] under [path]; the new done count, or null when cancelled. Throws IOException on read errors. */
    private fun add(ctx: Context, z: ZipOutputStream, f: File, path: String, skip: File, start: Long, total: Long, progress: ArcProgress): Long? {
        if (!progress.step(start, total)) return null
        if (f.path == skip.path) return start   // the new archive itself, when it lies in a packed folder
        val dir = Transfer.isDirectory(ctx, f) ?: throw IOException("gone")
        if (dir) {
            z.putNextEntry(ZipEntry("$path/"))
            z.closeEntry()
            var done = start
            for (c in Transfer.children(ctx, f)) done = add(ctx, z, c, "$path/${c.name}", skip, done, total, progress) ?: return null
            return done
        }
        val entry = ZipEntry(path)
        if (!Saf.isSaf(f)) entry.time = f.lastModified()
        z.putNextEntry(entry)
        val input = Transfer.openInput(ctx, f) ?: throw IOException("unreadable")
        val done = input.use { copy(it, z, start, total, progress) } ?: return null
        z.closeEntry()
        return done
    }
}
