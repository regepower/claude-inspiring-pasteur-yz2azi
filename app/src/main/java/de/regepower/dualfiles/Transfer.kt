package de.regepower.dualfiles

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copy, move and delete for local files and for folders from the storage access framework (Saf).
 * Local-to-local uses [FileOps]; anything touching a Saf folder streams the bytes through the provider.
 */
/** Thrown inside a copy loop when the user cancels. */
internal class Cancelled : IOException("cancelled")

/**
 * Progress of a file operation: [done] of [total] (bytes, or entries for delete). [add] reports to the
 * listener, which can cancel; loops check [cancelled] and stop.
 */
internal class Meter(private val listener: ArcProgress) {
    var total = 0L
    var done = 0L
    @Volatile var cancelled = false

    fun add(n: Long) {
        done += n
        report()
    }

    fun report() {
        if (!listener.step(done, total)) cancelled = true
    }

    companion object {
        /** Stream copy that counts into [m] and throws [Cancelled] when it is cancelled. */
        fun pump(i: InputStream, o: OutputStream, m: Meter?) {
            if (m == null) {
                i.copyTo(o)
                return
            }
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) return
                o.write(buf, 0, n)
                m.add(n.toLong())
                if (m.cancelled) throw Cancelled()
            }
        }
    }
}

/** What to do when a name already exists in the target. */
internal enum class Clash { OVERWRITE, SKIP, RENAME }

internal object Transfer {

    /** [overwrite]: an existing file of the same name is replaced, an existing folder is merged into. */
    fun copy(ctx: Context, src: File, dstDir: File, overwrite: Boolean = false, m: Meter? = null): Boolean {
        if (!Saf.isSaf(src) && !Saf.isSaf(dstDir)) return FileOps.copy(src, dstDir, overwrite, m)
        // Refuse to copy a folder into itself
        if (isDirectory(ctx, src) == true && (dstDir.path == src.path || dstDir.path.startsWith(src.path + "/"))) return false
        return try {
            copyTree(ctx, src, dstDir, overwrite, m)
        } catch (e: IOException) {
            false
        }
    }

    fun move(ctx: Context, src: File, dstDir: File, overwrite: Boolean = false, m: Meter? = null): Boolean {
        if (src.parentFile?.path == dstDir.path) return true
        if (!Saf.isSaf(src) && !Saf.isSaf(dstDir)) return FileOps.move(src, dstDir, overwrite, m)
        // Only delete the source once the whole copy succeeded
        return copy(ctx, src, dstDir, overwrite, m) && delete(ctx, src)
    }

    /** [m] counts one per deleted entry. */
    fun delete(ctx: Context, f: File, m: Meter? = null): Boolean {
        if (!Saf.isSaf(f)) return FileOps.delete(f, m)
        if (m?.cancelled == true) return false
        var ok = true
        if (isDirectory(ctx, f) == true) {
            for (child in children(ctx, f)) {
                if (!delete(ctx, child, m)) ok = false
                if (m?.cancelled == true) return false
            }
            if (!ok) return false
        }
        val gone = Saf.delete(ctx, f)
        m?.add(1)
        return gone
    }

    /** Bytes in [f] (with everything below it). */
    fun size(ctx: Context, f: File): Long = when {
        isDirectory(ctx, f) == true -> children(ctx, f).sumOf { size(ctx, it) }
        Saf.isSaf(f) -> Saf.stat(ctx, f)?.size ?: 0
        else -> f.length()
    }

    /** Entries in [f], itself included. */
    fun count(ctx: Context, f: File): Long =
        1 + if (isDirectory(ctx, f) == true) children(ctx, f).sumOf { count(ctx, it) } else 0

    /** Renames [f] in its folder; false if the name is taken or invalid. */
    fun rename(ctx: Context, f: File, name: String): Boolean {
        if (name.isEmpty() || name.contains('/') || name == "." || name == "..") return false
        val parent = f.parentFile ?: return false
        if (isDirectory(ctx, File(parent, name)) != null) return false
        return if (Saf.isSaf(f)) Saf.rename(ctx, f, name) else f.renameTo(File(parent, name))
    }

    /** Creates the folder [name] in [dir]; false if it exists or cannot be created. */
    fun mkdir(ctx: Context, dir: File, name: String): Boolean {
        if (name.isEmpty() || name.contains('/') || name == "." || name == "..") return false
        if (isDirectory(ctx, File(dir, name)) != null) return false
        return if (Saf.isSaf(dir)) Saf.createChild(ctx, dir, name, true) != null else File(dir, name).mkdir()
    }

    private fun copyTree(ctx: Context, src: File, dstDir: File, overwrite: Boolean, m: Meter?): Boolean {
        if (m?.cancelled == true) return false
        val dir = isDirectory(ctx, src) ?: return false
        // Overwrite: reuse an existing entry of the same kind (file contents are replaced, folders merged)
        val existing = if (overwrite) File(dstDir, src.name).takeIf { isDirectory(ctx, it) == dir } else null
        val target = existing ?: createChild(ctx, dstDir, src.name, dir) ?: return false
        if (!dir) return streamCopy(ctx, src, target, m)
        var ok = true
        for (child in children(ctx, src)) {
            if (!copyTree(ctx, child, target, overwrite, m)) ok = false
            if (m?.cancelled == true) return false
        }
        return ok
    }

    /** null if [f] does not exist. */
    fun isDirectory(ctx: Context, f: File): Boolean? =
        if (Saf.isSaf(f)) Saf.stat(ctx, f)?.isDir
        else if (f.exists()) f.isDirectory
        else null

    fun children(ctx: Context, f: File): List<File> =
        if (Saf.isSaf(f)) Saf.list(ctx, f).map { File(f, it.name) }
        else f.listFiles().orEmpty().toList()

    fun createChild(ctx: Context, dir: File, name: String, isDir: Boolean): File? =
        if (Saf.isSaf(dir)) Saf.createChild(ctx, dir, name, isDir)
        else {
            val t = FileOps.uniqueTarget(dir, name)
            if (isDir) t.takeIf { it.mkdirs() } else t.takeIf { it.createNewFile() }
        }

    private fun streamCopy(ctx: Context, src: File, dst: File, m: Meter?): Boolean {
        val input: InputStream = openInput(ctx, src) ?: return false
        val output: OutputStream = openOutput(ctx, dst) ?: run {
            input.close()
            return false
        }
        try {
            input.use { i -> output.use { o -> Meter.pump(i, o, m) } }
        } catch (e: Cancelled) {
            delete(ctx, dst)   // no half-copied file stays behind
            return false
        }
        return true
    }

    fun openInput(ctx: Context, f: File): InputStream? =
        if (Saf.isSaf(f)) Saf.docUri(ctx, f)?.let { ctx.contentResolver.openInputStream(it) }
        else FileInputStream(f)

    fun openOutput(ctx: Context, f: File): OutputStream? =
        if (Saf.isSaf(f)) Saf.docUri(ctx, f)?.let { ctx.contentResolver.openOutputStream(it, "wt") }
        else FileOutputStream(f)
}
