package com.personal.docscanner.data.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Turns a PDF into page images.
 *
 * Built on the platform's own [PdfRenderer], so there is no PDF library to add —
 * and no licence to worry about for a personal build.
 *
 * The pages that come out are deliberately **not** re-processed. A PDF exported
 * from another scanner app has already been cropped, deskewed and contrast-
 * corrected; running edge detection and shadow removal over it again would
 * find edges in the page content and make it worse, not better.
 */
class PdfImporter(private val context: Context) {

    data class Page(val index: Int, val bitmap: Bitmap)

    data class Info(val name: String, val pageCount: Int)

    class NotReadable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Rendering resolution. 200 dpi keeps small print legible and OCR-able
     * without producing images that are pointlessly large — the source is a
     * scan, so there is no detail beyond its own capture resolution to recover.
     */
    private val targetDpi = 200f

    /** Nothing on a phone screen or in an export needs more than this. */
    private val maxEdgePx = 2400

    /** Reads the name and page count without rendering anything. */
    suspend fun inspect(uri: Uri): Info = withContext(Dispatchers.IO) {
        openRenderer(uri).use { handle ->
            Info(displayName(uri), handle.renderer.pageCount)
        }
    }

    /**
     * Renders every page, handing each one to [onPage] as it is produced.
     *
     * Streaming rather than returning a list: a fifty-page case file rendered
     * at 200 dpi is hundreds of megabytes of bitmap, far more than a phone will
     * hold at once. Each page is recycled by the caller before the next starts.
     */
    suspend fun renderPages(
        uri: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onPage: suspend (Page) -> Unit
    ) = withContext(Dispatchers.IO) {
        openRenderer(uri).use { handle ->
            val count = handle.renderer.pageCount
            for (index in 0 until count) {
                coroutineContext.ensureActive()
                onProgress(index, count)
                handle.renderer.openPage(index).use { page ->
                    onPage(Page(index, renderToBitmap(page)))
                }
            }
            onProgress(count, count)
        }
    }

    private fun renderToBitmap(page: PdfRenderer.Page): Bitmap {
        // PdfRenderer reports size in points (1/72 inch).
        val scale = targetDpi / 72f
        var width = (page.width * scale).roundToInt().coerceAtLeast(1)
        var height = (page.height * scale).roundToInt().coerceAtLeast(1)

        val longest = maxOf(width, height)
        if (longest > maxEdgePx) {
            val shrink = maxEdgePx.toFloat() / longest
            width = (width * shrink).roundToInt().coerceAtLeast(1)
            height = (height * shrink).roundToInt().coerceAtLeast(1)
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // A PDF page is transparent where nothing is drawn. Without a white
        // ground the exported JPEG would render those areas black.
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun openRenderer(uri: Uri): RendererHandle {
        val descriptor: ParcelFileDescriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw NotReadable("could not open the file")
        } catch (t: Throwable) {
            throw NotReadable(t.message ?: "could not open the file", t)
        }
        return try {
            RendererHandle(descriptor, PdfRenderer(descriptor))
        } catch (t: Throwable) {
            descriptor.close()
            // The common causes are a password-protected PDF and a file that is
            // not a PDF at all; both surface here as the same exception type.
            throw NotReadable(
                "the file is not a readable PDF (it may be password protected)", t
            )
        }
    }

    fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        val raw = fromProvider ?: uri.lastPathSegment.orEmpty()
        return raw.substringAfterLast('/').removeSuffix(".pdf").ifEmpty { "مستند" }
    }

    /**
     * Ties the descriptor's lifetime to the renderer's so neither leaks.
     * Closeable rather than AutoCloseable so Kotlin's `use` applies directly.
     */
    private class RendererHandle(
        private val descriptor: ParcelFileDescriptor,
        val renderer: PdfRenderer
    ) : Closeable {
        override fun close() {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }
}
