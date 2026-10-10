package de.regepower.dualfiles

import android.webkit.MimeTypeMap
import java.io.File

/** Plain java.io file operations. Every function returns true on success. */
object FileOps {

    /** MIME type from the file extension, or null if unknown. */
    fun mime(f: File): String? {
        val ext = f.extension.lowercase()
        return if (ext.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    /** `name`, or `name (1)`, `name (2)` … if it already exists in [dir]. */
    fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (target.exists()) {
            target = File(dir, "$base ($n)$ext")
            n++
        }
        return target
    }

    /** [overwrite]: an existing file of the same name is replaced, an existing folder is merged into. */
    fun copy(src: File, dstDir: File, overwrite: Boolean = false): Boolean {
        if (src.isDirectory && isInside(dstDir, src)) return false
        val target = if (overwrite) File(dstDir, src.name) else uniqueTarget(dstDir, src.name)
        if (overwrite && target.canonicalPath == src.canonicalPath) return true
        return copyTo(src, target)
    }

    private fun copyTo(src: File, dst: File): Boolean {
        if (!src.isDirectory) {
            if (dst.isDirectory) return false
            return try {
                src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
                true
            } catch (e: java.io.IOException) {
                false
            }
        }
        if (!dst.isDirectory && !dst.mkdirs()) return false
        var ok = true
        for (child in src.listFiles().orEmpty()) {
            if (!copyTo(child, File(dst, child.name))) ok = false
        }
        return ok
    }

    fun move(src: File, dstDir: File, overwrite: Boolean = false): Boolean {
        if (src.isDirectory && isInside(dstDir, src)) return false
        if (src.parentFile?.canonicalPath == dstDir.canonicalPath) return true
        val target = if (overwrite) File(dstDir, src.name) else uniqueTarget(dstDir, src.name)
        if (target.exists()) {
            // Overwrite: a file replaces the file, a folder is merged into the folder
            if (src.isFile && target.isFile && target.delete() && src.renameTo(target)) return true
            return copyTo(src, target) && delete(src)
        }
        if (src.renameTo(target)) return true
        // Different volume: copy, then delete the source only if everything was copied.
        return copyTo(src, target) && delete(src)
    }

    fun delete(f: File): Boolean {
        var ok = true
        if (f.isDirectory) {
            for (child in f.listFiles().orEmpty()) {
                if (!delete(child)) ok = false
            }
        }
        return f.delete() && ok
    }

    /** True if [dir] is [parent] itself or lies below it. */
    private fun isInside(dir: File, parent: File): Boolean {
        val d = dir.canonicalPath
        val p = parent.canonicalPath
        return d == p || d.startsWith(p + File.separator)
    }
}
