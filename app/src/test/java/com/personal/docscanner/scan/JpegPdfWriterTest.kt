package com.personal.docscanner.scan

import com.personal.docscanner.data.model.PdfPageSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/**
 * The PDF writer emits a binary format by hand, byte offset by byte offset.
 * A file it gets subtly wrong still looks like a PDF and may even open in a
 * lenient reader, so the checks here are on the structure itself rather than on
 * whether something managed to display it.
 */
class JpegPdfWriterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun page(width: Int, height: Int, marker: Byte) = JpegPdfWriter.Page(
        jpeg = ByteArray(64) { marker },
        pixelWidth = width,
        pixelHeight = height
    )

    private fun write(
        pages: List<JpegPdfWriter.Page>,
        pageSize: PdfPageSize = PdfPageSize.A4
    ): ByteArray {
        val target = File(folder.root, "out.pdf")
        JpegPdfWriter.write(pages, target, pageSize)
        return target.readBytes()
    }

    @Test
    fun `writes a pdf header and trailer`() {
        val bytes = write(listOf(page(800, 1000, 0x11)))
        val text = bytes.toString(Charsets.ISO_8859_1)

        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Type /Catalog"))
    }

    @Test
    fun `embeds the jpeg bytes verbatim as DCTDecode`() {
        val jpeg = ByteArray(128) { (it % 251).toByte() }
        val bytes = write(listOf(JpegPdfWriter.Page(jpeg, 640, 480)))

        assertTrue(
            "image must be embedded as JPEG, not re-encoded",
            bytes.toString(Charsets.ISO_8859_1).contains("/Filter /DCTDecode")
        )
        assertTrue("the jpeg bytes must survive untouched", bytes.indexOfSlice(jpeg) >= 0)
    }

    @Test
    fun `page count matches the pages given`() {
        val bytes = write(listOf(page(800, 1000, 1), page(800, 1000, 2), page(800, 1000, 3)))
        assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("/Count 3"))
    }

    @Test
    fun `every xref offset points at the object it claims`() {
        val bytes = write(listOf(page(800, 1000, 1), page(600, 900, 2)))
        val text = bytes.toString(Charsets.ISO_8859_1)

        val offsets = Regex("""(\d{10}) 00000 n """).findAll(text)
            .map { it.groupValues[1].toInt() }
            .toList()

        // Two pages: catalogue, page tree, and three objects per page.
        assertEquals(2 + 2 * 3, offsets.size)
        offsets.forEachIndexed { index, offset ->
            val expected = "${index + 1} 0 obj"
            val actual = text.substring(offset, offset + expected.length)
            assertEquals("xref entry ${index + 1} points at the wrong byte", expected, actual)
        }
    }

    @Test
    fun `startxref points at the xref table`() {
        val bytes = write(listOf(page(800, 1000, 1)))
        val text = bytes.toString(Charsets.ISO_8859_1)

        val declared = Regex("""startxref\s+(\d+)""").find(text)!!.groupValues[1].toInt()
        assertEquals("xref", text.substring(declared, declared + 4))
    }

    /**
     * The regression this file exists for. The app's default locale is Arabic,
     * and a number formatted through it comes out in Arabic-Indic digits — which
     * inside PDF syntax produces a file nothing can open.
     */
    @Test
    fun `numbers stay in western digits under an arabic locale`() {
        Locale.setDefault(Locale("ar", "EG"))
        val text = write(listOf(page(800, 1000, 1))).toString(Charsets.ISO_8859_1)

        val mediaBox = Regex("""/MediaBox \[([^\]]*)\]""").find(text)!!.groupValues[1]
        assertTrue(
            "MediaBox must be ASCII digits and dots, was: $mediaBox",
            mediaBox.all { it.isDigit() || it == '.' || it == ' ' || it == '-' }
        )
        assertTrue(text.contains("cm /Im0 Do"))
    }

    @Test
    fun `a4 pages follow the image orientation`() {
        val portrait = write(listOf(page(800, 1000, 1))).toString(Charsets.ISO_8859_1)
        assertTrue(portrait.contains("/MediaBox [0 0 595.00 842.00]"))

        val landscape = write(listOf(page(1000, 800, 1))).toString(Charsets.ISO_8859_1)
        assertTrue(landscape.contains("/MediaBox [0 0 842.00 595.00]"))
    }

    /**
     * FIT used to write one point per pixel, which made a scan open as a sheet
     * over a foot across. Treating it as 200 dpi keeps it near real paper.
     */
    @Test
    fun `fit sizes the page from the assumed scan resolution`() {
        val text = write(listOf(page(1600, 2200, 1)), PdfPageSize.FIT)
            .toString(Charsets.ISO_8859_1)

        // 1600px at 200 dpi is 8 inches, which is 576 points.
        assertTrue("was: ${Regex("""/MediaBox \[[^\]]*\]""").find(text)?.value}",
            text.contains("/MediaBox [0 0 576.00 792.00]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses to write nothing`() {
        write(emptyList())
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) {
                if (this[start + i] != needle[i]) continue@outer
            }
            return start
        }
        return -1
    }
}
