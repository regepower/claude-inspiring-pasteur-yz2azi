package de.regepower.dualfiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.IOException

/**
 * Converts pictures to WebP with Android's own encoder. The EXIF rotation is applied to the pixels;
 * EXIF data of JPEG pictures (date, camera, GPS) is carried over by the native part.
 */
internal object Webp {
    /** Pictures Android can decode (WebP itself excluded). */
    fun canConvert(f: File): Boolean {
        val ext = f.extension.lowercase()
        return ext != "webp" && FileOps.mime(f)?.startsWith("image/") == true && ext != "svg"
    }

    fun targetName(f: File) = f.nameWithoutExtension.ifEmpty { f.name } + ".webp"

    /** [quality] 0–100 for lossy, -1 for lossless. Writes [dstDir]/name.webp. */
    fun convert(ctx: Context, src: File, dstDir: File, quality: Int, overwrite: Boolean): Boolean {
        val work = File(ctx.cacheDir, "webp").apply { deleteRecursively(); mkdirs() }
        try {
            // Saf pictures are decoded from a cache copy
            val local = if (Saf.isSaf(src)) {
                val copy = File(work, "in." + src.extension)
                val input = Transfer.openInput(ctx, src) ?: return false
                input.use { i -> copy.outputStream().use { i.copyTo(it) } }
                copy
            } else src
            var bmp = BitmapFactory.decodeFile(local.path) ?: return false
            val rotated = rotate(bmp, orientation(local))
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated

            val out = File(work, targetName(src))
            val format = if (quality < 0) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            val written = out.outputStream().use { bmp.compress(format, if (quality < 0) 100 else quality, it) }
            val w = bmp.width
            val h = bmp.height
            val alpha = bmp.hasAlpha()
            bmp.recycle()
            if (!written) return false
            if (NativeLib.ok && local.extension.lowercase() in setOf("jpg", "jpeg")) {
                NativeLib.jpegExif(local.path)?.let { NativeLib.webpAddExif(out.path, it, w, h, alpha) }
            }
            if (!Saf.isSaf(src)) out.setLastModified(src.lastModified())
            return Transfer.copy(ctx, out, dstDir, overwrite)
        } catch (e: IOException) {
            return false
        } catch (e: OutOfMemoryError) {
            return false   // picture too big for memory
        } finally {
            work.deleteRecursively()
        }
    }

    private fun orientation(f: File): Int = try {
        ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Applies an EXIF orientation to the pixels; returns [b] itself when nothing changes. */
    private fun rotate(b: Bitmap, o: Int): Bitmap {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }
}
