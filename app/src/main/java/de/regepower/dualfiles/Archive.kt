package de.regepower.dualfiles

import android.content.Context
import android.os.CancellationSignal
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 7z extraction in C (LZMA SDK, see src/main/cpp). */
internal object SevenZip {
    init {
        System.loadLibrary("sevenz")
    }

    /** Extracts [archive] into the existing folder [outDir]: number of entries, or a negative error code. */
    @JvmStatic external fun extract(archive: String, outDir: String, cancel: CancellationSignal?): Int
}

/**
 * Unpacking ZIP and 7z, packing ZIP. Works for local and Saf folders: Saf archives are read from a copy in
 * the cache, and results for a Saf folder are made in the cache and then copied there.
 */
internal object Archive {
    /** Error text resource for a failed run; null means success. */
    class Result(val error: Int?)

    fun canExtract(f: File) = f.extension.lowercase() in setOf("zip", "7z")

    /** Unpacks [archive] into a new folder (named like the archive) in [dstDir]. */
    fun extract(ctx: Context, archive: File, dstDir: File, cancel: CancellationSignal): Result {
        val work = File(ctx.cacheDir, "archive").apply { deleteRecursively(); mkdirs() }
        try {
            val local = if (Saf.isSaf(archive)) {
                val copy = File(work, "in." + archive.extension)
                val input = Transfer.openInput(ctx, archive) ?: return Result(R.string.arc_read)
                input.use { i -> copy.outputStream().use { i.copyTo(it) } }
                copy
            } else archive
            val name = archive.nameWithoutExtension.ifEmpty { "archive" }
            val out = if (Saf.isSaf(dstDir)) File(File(work, "out"), name) else FileOps.uniqueTarget(dstDir, name)
            if (!out.mkdirs()) return Result(R.string.arc_write)
            val error = if (archive.extension.lowercase() == "7z") sevenZip(local, out, cancel) else unzip(local, out, cancel)
            if (error != null) {
                out.deleteRecursively()
                return Result(error)
            }
            if (Saf.isSaf(dstDir) && !Transfer.copy(ctx, out, dstDir)) return Result(R.string.arc_write)
            return Result(null)
        } catch (e: IOException) {
            return Result(R.string.arc_read)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun sevenZip(archive: File, out: File, cancel: CancellationSignal): Int? {
        val r = try {
            SevenZip.extract(archive.path, out.path, cancel)
        } catch (e: UnsatisfiedLinkError) {
            return R.string.arc_unsupported
        }
        return when {
            r >= 0 -> null
            r == -2 -> R.string.arc_memory                  // SZ_ERROR_MEM
            r == -4 -> R.string.arc_unsupported             // encrypted or unknown method
            r == -9 -> R.string.arc_write                   // SZ_ERROR_WRITE
            r == -100 -> R.string.arc_bad_path
            r == -101 -> R.string.arc_cancelled
            else -> R.string.arc_damaged                    // data, CRC, not an archive, ...
        }
    }

    /** ZIP with UTF-8 names; older Windows archives (CP437 names) are read on a second try. */
    private fun unzip(archive: File, out: File, cancel: CancellationSignal): Int? {
        return try {
            unzipWith(archive, out, cancel, Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            out.listFiles()?.forEach { it.deleteRecursively() }
            try {
                unzipWith(archive, out, cancel, Charset.forName("IBM437"))
            } catch (e2: Exception) {
                R.string.arc_damaged
            }
        } catch (e: ZipException) {
            if (e.message?.contains("encrypt", true) == true) R.string.arc_unsupported else R.string.arc_damaged
        } catch (e: IOException) {
            R.string.arc_write
        }
    }

    private fun unzipWith(archive: File, out: File, cancel: CancellationSignal, cs: Charset): Int? {
        val root = out.canonicalPath + File.separator
        ZipFile(archive, cs).use { zip ->
            for (e in zip.entries()) {
                if (cancel.isCanceled) return R.string.arc_cancelled
                val target = File(out, e.name)
                // No entry may leave the target folder ("../" in the name)
                if (!target.canonicalPath.startsWith(root)) return R.string.arc_bad_path
                if (e.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(e).use { i -> target.outputStream().use { i.copyTo(it) } }
                if (e.time > 0) target.setLastModified(e.time)
            }
        }
        return null
    }

    /** Packs [items] (files and folders) into [dstDir]/[name]. */
    fun zip(ctx: Context, items: List<File>, dstDir: File, name: String, cancel: CancellationSignal): Result {
        val target = Transfer.createChild(ctx, dstDir, name, false) ?: return Result(R.string.arc_write)
        val ok = try {
            val output = Transfer.openOutput(ctx, target) ?: throw IOException()
            ZipOutputStream(output.buffered()).use { z ->
                for (f in items) if (!add(ctx, z, f, f.name, target, cancel)) return@use false
                true
            }
        } catch (e: IOException) {
            false
        }
        if (!ok) Transfer.delete(ctx, target)
        return Result(if (ok) null else if (cancel.isCanceled) R.string.arc_cancelled else R.string.arc_write)
    }

    private fun add(ctx: Context, z: ZipOutputStream, f: File, path: String, skip: File, cancel: CancellationSignal): Boolean {
        if (cancel.isCanceled) return false
        if (f.path == skip.path) return true   // the new archive itself, when it lies in a packed folder
        val dir = Transfer.isDirectory(ctx, f) ?: return false
        if (dir) {
            z.putNextEntry(ZipEntry("$path/"))
            z.closeEntry()
            for (c in Transfer.children(ctx, f)) if (!add(ctx, z, c, "$path/${c.name}", skip, cancel)) return false
            return true
        }
        val entry = ZipEntry(path)
        if (!Saf.isSaf(f)) entry.time = f.lastModified()
        z.putNextEntry(entry)
        val input = Transfer.openInput(ctx, f) ?: return false
        input.use { it.copyTo(z) }
        z.closeEntry()
        return true
    }
}
