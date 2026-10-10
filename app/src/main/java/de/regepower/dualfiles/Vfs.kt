package de.regepower.dualfiles

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Folders that are not plain files: Saf folders ("/saf/…", Google Drive and other apps) and network
 * storages ("/net/…", FTP and WebDAV). Everything outside the viewer and sharing goes through here.
 */
internal object Vfs {
    fun isVirtual(f: File) = Saf.isSaf(f) || Net.isNet(f)

    fun rootFiles(ctx: Context): List<File> = Saf.rootFiles(ctx) + Net.rootFiles(ctx)

    fun name(ctx: Context, root: File): String = if (Net.isNet(root)) Net.name(ctx, root) else Saf.name(ctx, root)

    fun list(ctx: Context, dir: File): List<Saf.Doc> = if (Net.isNet(dir)) Net.list(ctx, dir) else Saf.list(ctx, dir)

    fun stat(ctx: Context, f: File): Saf.Doc? = if (Net.isNet(f)) Net.stat(ctx, f) else Saf.stat(ctx, f)

    fun createChild(ctx: Context, dir: File, name: String, isDir: Boolean): File? =
        if (Net.isNet(dir)) Net.createChild(ctx, dir, name, isDir) else Saf.createChild(ctx, dir, name, isDir)

    fun delete(ctx: Context, f: File): Boolean = if (Net.isNet(f)) Net.delete(ctx, f) else Saf.delete(ctx, f)

    fun rename(ctx: Context, f: File, name: String): Boolean = if (Net.isNet(f)) Net.rename(ctx, f, name) else Saf.rename(ctx, f, name)

    fun openInput(ctx: Context, f: File): InputStream? =
        if (Net.isNet(f)) Net.openInput(ctx, f)
        else Saf.docUri(ctx, f)?.let { ctx.contentResolver.openInputStream(it) }

    fun openOutput(ctx: Context, f: File): OutputStream? =
        if (Net.isNet(f)) Net.openOutput(ctx, f)
        else Saf.docUri(ctx, f)?.let { ctx.contentResolver.openOutputStream(it, "wt") }

    fun remove(ctx: Context, root: File) = if (Net.isNet(root)) Net.remove(ctx, root) else Saf.remove(ctx, root)
}
