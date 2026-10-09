package de.regepower.dualfiles

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Printing through Android's print service: PDFs are passed through, images are placed on one page. */
internal object FilePrint {
    fun canPrint(mime: String?) = mime == "application/pdf" || mime?.startsWith("image/") == true

    fun print(ctx: Context, uri: Uri, name: String, mime: String) {
        val pm = ctx.getSystemService(PrintManager::class.java) ?: return
        val adapter = if (mime == "application/pdf") PdfAdapter(ctx, uri, name) else ImageAdapter(ctx, uri, name)
        pm.print(name, adapter, null)
    }

    private class PdfAdapter(val ctx: Context, val uri: Uri, val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) return callback.onLayoutCancelled()
            val info = PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.WriteResultCallback
        ) {
            try {
                val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("no input")
                input.use { i -> FileOutputStream(destination.fileDescriptor).use { o -> i.copyTo(o) } }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }

    private class ImageAdapter(val ctx: Context, val uri: Uri, val name: String) : PrintDocumentAdapter() {
        private var attributes: PrintAttributes? = null

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) return callback.onLayoutCancelled()
            attributes = newAttributes
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
                .setPageCount(1)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: PrintDocumentAdapter.WriteResultCallback
        ) {
            val attrs = attributes ?: return callback.onWriteFailed(null)
            val pdf = PrintedPdfDocument(ctx, attrs)
            try {
                // Decode at a size that fits a page, not the full camera resolution
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: throw IOException("no image")
                val page = pdf.startPage(0)
                val r = page.info.contentRect
                val scale = min(r.width() / bmp.width.toFloat(), r.height() / bmp.height.toFloat())
                val w = bmp.width * scale
                val h = bmp.height * scale
                val left = r.left + (r.width() - w) / 2
                val top = r.top + (r.height() - h) / 2
                page.canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), null)
                pdf.finishPage(page)
                FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
                callback.onWriteFinished(arrayOf(PageRange(0, 0)))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            } finally {
                pdf.close()
            }
        }
    }
}
