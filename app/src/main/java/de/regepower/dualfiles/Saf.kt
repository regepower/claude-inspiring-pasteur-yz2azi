package de.regepower.dualfiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Folders from Android's storage access framework (e.g. Google Drive, Nextcloud), chosen once by the user.
 * They have no file paths, so each one gets a virtual path "/saf/<id>/..." that the file lists use; the
 * names are resolved to document ids on every listing. Copy, move and delete do not work there yet.
 */
internal object Saf {
    const val PREFIX = "/saf/"
    private const val PREFS = "safroots"   // id -> tree uri

    class Doc(val docId: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    private val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSaf(f: File) = f.path.startsWith(PREFIX)

    private fun idOf(f: File) = f.path.removePrefix(PREFIX).substringBefore('/')

    /** Virtual root folders, one per chosen folder. */
    fun rootFiles(ctx: Context): List<File> = prefs(ctx).all.keys.sorted().map { File("$PREFIX$it") }

    /** Remembers a folder the user picked, with read and write access kept across restarts. */
    fun add(ctx: Context, uri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs(ctx).edit().putString(Integer.toHexString(uri.toString().hashCode()), uri.toString()).apply()
    }

    fun remove(ctx: Context, root: File) {
        val uri = treeUri(ctx, root) ?: return
        prefs(ctx).edit().remove(idOf(root)).apply()
        ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    private fun treeUri(ctx: Context, f: File): Uri? = prefs(ctx).getString(idOf(f), null)?.let { Uri.parse(it) }

    /** Name of the chosen folder, e.g. "Google Drive". */
    fun name(ctx: Context, root: File): String {
        val uri = treeUri(ctx, root) ?: return idOf(root)
        val docId = DocumentsContract.getTreeDocumentId(uri)
        return info(ctx, uri, docId)?.name ?: idOf(root)
    }

    private fun info(ctx: Context, treeUri: Uri, docId: String): Doc? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return ctx.contentResolver.query(docUri, columns, null, null, null)?.use { c ->
            if (c.moveToFirst()) row(c) else null
        }
    }

    private fun children(ctx: Context, treeUri: Uri, docId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        return ctx.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            val out = ArrayList<Doc>()
            while (c.moveToNext()) out.add(row(c))
            out
        }.orEmpty()
    }

    private fun row(c: android.database.Cursor) = Doc(
        docId = c.getString(0),
        name = c.getString(1) ?: "",
        isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
        size = if (c.isNull(3)) 0L else c.getLong(3),
        modified = if (c.isNull(4)) 0L else c.getLong(4),
    )

    /** Document id of [f] (a virtual path) found by walking its names from the chosen folder. */
    private fun resolve(ctx: Context, f: File): Pair<Uri, String>? {
        val tree = treeUri(ctx, f) ?: return null
        var docId = DocumentsContract.getTreeDocumentId(tree)
        val parts = f.path.removePrefix("$PREFIX${idOf(f)}").split('/').filter { it.isNotEmpty() }
        for (name in parts) {
            docId = children(ctx, tree, docId).firstOrNull { it.name == name }?.docId ?: return null
        }
        return Pair(tree, docId)
    }

    /** Entries of the virtual folder [f]. */
    fun list(ctx: Context, f: File): List<Doc> {
        val (tree, docId) = resolve(ctx, f) ?: return emptyList()
        return children(ctx, tree, docId)
    }

    /** Content uri for opening a file in another app. */
    fun docUri(ctx: Context, f: File): Uri? {
        val (tree, docId) = resolve(ctx, f) ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId)
    }

    /** Metadata of [f], or null if it does not exist. */
    fun stat(ctx: Context, f: File): Doc? {
        val (tree, docId) = resolve(ctx, f) ?: return null
        return info(ctx, tree, docId)
    }

    /** Creates a file or folder [name] in the virtual folder [dir]; returns its path (the provider may rename it). */
    fun createChild(ctx: Context, dir: File, name: String, isDir: Boolean): File? {
        val (tree, parentId) = resolve(ctx, dir) ?: return null
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val mime = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(name).extension.lowercase()) ?: "application/octet-stream"
        val created = DocumentsContract.createDocument(ctx.contentResolver, parentUri, mime, name) ?: return null
        val actual = info(ctx, tree, DocumentsContract.getDocumentId(created))?.name ?: name
        return File(dir, actual)
    }

    fun delete(ctx: Context, f: File): Boolean {
        val (tree, docId) = resolve(ctx, f) ?: return false
        return DocumentsContract.deleteDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId))
    }
}
