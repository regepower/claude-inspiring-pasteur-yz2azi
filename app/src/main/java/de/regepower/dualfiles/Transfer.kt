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
/** What to do when a name already exists in the target. */
internal enum class Clash { OVERWRITE, SKIP, RENAME }

internal object Transfer {

    /** [overwrite]: an existing file of the same name is replaced, an existing folder is merged into. */
    fun copy(ctx: Context, src: File, dstDir: File, overwrite: Boolean = false): Boolean {
        if (!Saf.isSaf(src) && !Saf.isSaf(dstDir)) return FileOps.copy(src, dstDir, overwrite)
        // Refuse to copy a folder into itself
        if (isDirectory(ctx, src) == true && (dstDir.path == src.path || dstDir.path.startsWith(src.path + "/"))) return false
        return try {
            copyTree(ctx, src, dstDir, overwrite)
        } catch (e: IOException) {
            false
        }
    }

    fun move(ctx: Context, src: File, dstDir: File, overwrite: Boolean = false): Boolean {
        if (src.parentFile?.path == dstDir.path) return true
        if (!Saf.isSaf(src) && !Saf.isSaf(dstDir)) return FileOps.move(src, dstDir, overwrite)
        // Only delete the source once the whole copy succeeded
        return copy(ctx, src, dstDir, overwrite) && delete(ctx, src)
    }

    fun delete(ctx: Context, f: File): Boolean {
        if (!Saf.isSaf(f)) return FileOps.delete(f)
        var ok = true
        if (isDirectory(ctx, f) == true) {
            for (child in children(ctx, f)) if (!delete(ctx, child)) ok = false
            if (!ok) return false
        }
        return Saf.delete(ctx, f)
    }

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

    private fun copyTree(ctx: Context, src: File, dstDir: File, overwrite: Boolean): Boolean {
        val dir = isDirectory(ctx, src) ?: return false
        // Overwrite: reuse an existing entry of the same kind (file contents are replaced, folders merged)
        val existing = if (overwrite) File(dstDir, src.name).takeIf { isDirectory(ctx, it) == dir } else null
        val target = existing ?: createChild(ctx, dstDir, src.name, dir) ?: return false
        if (!dir) return streamCopy(ctx, src, target)
        var ok = true
        for (child in children(ctx, src)) if (!copyTree(ctx, child, target, overwrite)) ok = false
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

    private fun streamCopy(ctx: Context, src: File, dst: File): Boolean {
        val input: InputStream = openInput(ctx, src) ?: return false
        val output: OutputStream = openOutput(ctx, dst) ?: run {
            input.close()
            return false
        }
        input.use { i -> output.use { o -> i.copyTo(o) } }
        return true
    }

    fun openInput(ctx: Context, f: File): InputStream? =
        if (Saf.isSaf(f)) Saf.docUri(ctx, f)?.let { ctx.contentResolver.openInputStream(it) }
        else FileInputStream(f)

    fun openOutput(ctx: Context, f: File): OutputStream? =
        if (Saf.isSaf(f)) Saf.docUri(ctx, f)?.let { ctx.contentResolver.openOutputStream(it, "wt") }
        else FileOutputStream(f)
}
