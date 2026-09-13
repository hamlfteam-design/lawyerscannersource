package com.personal.docscanner.scan

import com.personal.docscanner.data.model.PdfPageSize
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Writes a PDF whose pages are JPEG images, embedded as JPEG.
 *
 * This exists because Android's own [android.graphics.pdf.PdfDocument] cannot
 * do it. PdfDocument takes a Bitmap — raw pixels — and stores them with
 * lossless compression. A scan handed to it comes out at three megabytes a page
 * no matter what JPEG quality it was decoded from, because by the time
 * PdfDocument sees it the JPEG is long gone. Re-compressing the bitmap first
 * only bakes in artefacts and, since noise compresses worse than smooth
 * gradients, tends to make the file *bigger*.
 *
 * PDF supports JPEG natively as the DCTDecode filter: the JPEG bytes go into
 * the file verbatim, and quality then means what it says. A five-page contract
 * lands at a couple of hundred kilobytes a page instead of three megabytes.
 *
 * The file this produces is a plain PDF 1.4 — catalogue, page tree, one image
 * XObject per page — which is all an image-per-page document ever needs.
 */
object JpegPdfWriter {

    /** One page: the JPEG bytes and the pixel dimensions they decode to. */
    data class Page(val jpeg: ByteArray, val pixelWidth: Int, val pixelHeight: Int) {
        // Generated equals/hashCode would compare the ByteArray by identity,
        // which is misleading enough to be worth stating.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * Resolution assumed when a page keeps its own proportions. Pixels are not
     * points — treating them as such gives a sheet several feet across, which
     * prints and previews wrongly everywhere.
     */
    private const val ASSUMED_DPI = 200f

    fun write(pages: List<Page>, target: File, pageSize: PdfPageSize): File {
        require(pages.isNotEmpty()) { "nothing to write" }
        target.parentFile?.mkdirs()

        val objectCount = 2 + pages.size * 3
        val offsets = LongArray(objectCount + 1)

        Counting(FileOutputStream(target).buffered()).use { out ->
            out.ascii("%PDF-1.4\n")
            // High bytes in a comment tell anything that sniffs the file that it
            // is binary, so a transport does not helpfully "fix" the line endings.
            out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xE4.toByte(), 0xE5.toByte(), 0x0A))

            offsets[1] = out.count
            out.ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

            offsets[2] = out.count
            val kids = pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
            out.ascii("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")

            pages.forEachIndexed { index, page ->
                val pageObject = 3 + index * 3
                val contentObject = pageObject + 1
                val imageObject = pageObject + 2

                val (pageWidth, pageHeight) = dimensions(page, pageSize)
                val box = fitCentered(page.pixelWidth, page.pixelHeight, pageWidth, pageHeight)

                offsets[pageObject] = out.count
                out.ascii(
                    "$pageObject 0 obj\n" +
                        "<< /Type /Page /Parent 2 0 R " +
                        "/MediaBox [0 0 ${num(pageWidth)} ${num(pageHeight)}] " +
                        "/Resources << /XObject << /Im0 $imageObject 0 R >> >> " +
                        "/Contents $contentObject 0 R >>\nendobj\n"
                )

                // PDF space has its origin at the bottom left, and an image
                // XObject is drawn into the unit square, so the matrix carries
                // both the size and the position.
                val content = "q ${num(box.width)} 0 0 ${num(box.height)} " +
                    "${num(box.left)} ${num(box.bottom)} cm /Im0 Do Q"
                val contentBytes = content.toByteArray(Charsets.ISO_8859_1)

                offsets[contentObject] = out.count
                out.ascii("$contentObject 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n")
                out.write(contentBytes)
                out.ascii("\nendstream\nendobj\n")

                offsets[imageObject] = out.count
                out.ascii(
                    "$imageObject 0 obj\n" +
                        "<< /Type /XObject /Subtype /Image " +
                        "/Width ${page.pixelWidth} /Height ${page.pixelHeight} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                        "/Filter /DCTDecode /Length ${page.jpeg.size} >>\nstream\n"
                )
                out.write(page.jpeg)
                out.ascii("\nendstream\nendobj\n")
            }

            val xref = out.count
            out.ascii("xref\n0 ${objectCount + 1}\n")
            out.ascii("0000000000 65535 f \n")
            for (i in 1..objectCount) {
                out.ascii(String.format(Locale.US, "%010d 00000 n \n", offsets[i]))
            }
            out.ascii(
                "trailer\n<< /Size ${objectCount + 1} /Root 1 0 R >>\n" +
                    "startxref\n$xref\n%%EOF\n"
            )
        }
        return target
    }

    private fun dimensions(page: Page, pageSize: PdfPageSize): Pair<Float, Float> =
        when (pageSize) {
            PdfPageSize.FIT ->
                page.pixelWidth * 72f / ASSUMED_DPI to page.pixelHeight * 72f / ASSUMED_DPI

            // Follow the image's orientation, so a landscape scan is not
            // letterboxed into a portrait sheet.
            else -> if (page.pixelWidth > page.pixelHeight) {
                pageSize.heightPt.toFloat() to pageSize.widthPt.toFloat()
            } else {
                pageSize.widthPt.toFloat() to pageSize.heightPt.toFloat()
            }
        }

    private data class Box(val left: Float, val bottom: Float, val width: Float, val height: Float)

    private fun fitCentered(srcW: Int, srcH: Int, pageW: Float, pageH: Float): Box {
        val scale = minOf(pageW / srcW, pageH / srcH)
        val width = srcW * scale
        val height = srcH * scale
        return Box((pageW - width) / 2f, (pageH - height) / 2f, width, height)
    }

    /**
     * Numbers must be written with a dot and Western digits. The device locale
     * here is Arabic, so anything that formats through the default locale would
     * emit Arabic-Indic digits and produce a file no reader can open.
     */
    private fun num(value: Float): String = String.format(Locale.US, "%.2f", value)

    /** The xref table needs the byte offset of every object. */
    private class Counting(private val delegate: OutputStream) : OutputStream() {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() = delegate.flush()

        override fun close() {
            delegate.flush()
            delegate.close()
        }
    }

    private fun OutputStream.ascii(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))
}
