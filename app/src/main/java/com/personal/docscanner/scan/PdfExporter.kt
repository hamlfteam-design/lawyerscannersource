package com.personal.docscanner.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.personal.docscanner.data.model.PdfPageSize
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Writes the pages of a document into a single PDF.
 *
 * Each page is re-encoded as JPEG at the requested quality and embedded as
 * JPEG — see [JpegPdfWriter] for why that is done by hand rather than through
 * Android's PdfDocument, which would store raw pixels and produce a file an
 * order of magnitude larger.
 */
object PdfExporter {

    /**
     * Default cap on a page's long edge.
     *
     * 2000 pixels is a little over 200 dpi across A4 — past the point where more
     * pixels make printed text any easier to read, or OCR any more accurate, and
     * every pixel beyond it is paid for in file size and in upload time.
     */
    const val DEFAULT_MAX_EDGE = 2000

    /**
     * @param pageFiles processed page images, in order
     * @param target    file to write
     * @param pageSize  fixed paper size, or [PdfPageSize.FIT] to match each image
     * @param quality   JPEG quality, 30..100
     * @param maxEdge   longest side any page is allowed to keep, in pixels
     * @return [target]
     */
    fun export(
        pageFiles: List<File>,
        target: File,
        pageSize: PdfPageSize = PdfPageSize.FIT,
        quality: Int = 85,
        maxEdge: Int = DEFAULT_MAX_EDGE
    ): File {
        require(pageFiles.isNotEmpty()) { "nothing to export" }

        val pages = pageFiles.mapNotNull { file -> encode(file, quality, maxEdge) }
        require(pages.isNotEmpty()) { "no readable pages" }

        return JpegPdfWriter.write(pages, target, pageSize)
    }

    private fun encode(file: File, quality: Int, maxEdge: Int): JpegPdfWriter.Page? {
        val bitmap = decodeLimited(file, maxEdge) ?: return null
        return try {
            val jpeg = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 100), out)
                out.toByteArray()
            }
            JpegPdfWriter.Page(jpeg, bitmap.width, bitmap.height)
        } catch (t: OutOfMemoryError) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes [file] no larger than [maxEdge] on its long side.
     *
     * inSampleSize only halves, so it gets close cheaply and a scale finishes
     * the job exactly — which matters because the compressor searches over this
     * value and needs small steps to actually change the result.
     */
    private fun decodeLimited(file: File, maxEdge: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sourceEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val wanted = maxEdge.coerceAtLeast(1)

        var sample = 1
        while (sourceEdge / (sample * 2) >= wanted) sample *= 2

        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null

        val decodedEdge = maxOf(decoded.width, decoded.height)
        if (decodedEdge <= wanted) return decoded

        val factor = wanted.toFloat() / decodedEdge
        val resized = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * factor).roundToInt().coerceAtLeast(1),
            (decoded.height * factor).roundToInt().coerceAtLeast(1),
            true
        )
        if (resized !== decoded) decoded.recycle()
        return resized
    }

    /** Renders a plain-text sidecar of the OCR result. */
    fun exportText(text: String, target: File): File {
        target.parentFile?.mkdirs()
        target.writeText(text)
        return target
    }
}
