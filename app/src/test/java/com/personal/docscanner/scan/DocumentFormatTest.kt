package com.personal.docscanner.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Format recognition decides what a scan gets snapped to, so a wrong answer
 * distorts the page rather than merely mislabelling it. The ratios of A4 and a
 * passport data page sit 0.006 apart, which is the whole difficulty here.
 */
class DocumentFormatTest {

    /** A quad measured off a photograph is never exact; this is a realistic miss. */
    private fun sizeFor(ratio: Float, shortEdge: Int = 1000, error: Float = 0f): Pair<Int, Int> =
        shortEdge to ((shortEdge * (ratio + error)).toInt())

    @Test
    fun `recognises a4`() {
        val (w, h) = sizeFor(1.4142f)
        assertEquals(DocumentFormat.A4, DocumentFormat.detect(w, h))
    }

    @Test
    fun `recognises an id card`() {
        val (w, h) = sizeFor(1.5857f)
        assertEquals(DocumentFormat.ID_CARD, DocumentFormat.detect(w, h))
    }

    /**
     * A4 is 1.4142 and a passport data page is 1.4205 — 0.44% apart, which is
     * finer than a photographed page can be measured. Shape cannot separate
     * them, so a page in that band is read as A4 on purpose: this app is for
     * case files, and treating a passport as A4 moves its ratio by 0.44% while
     * the reverse squeezes every page of a contract.
     */
    @Test
    fun `a passport-shaped page reads as a4, deliberately`() {
        val (w, h) = sizeFor(1.4205f)
        assertEquals(DocumentFormat.A4, DocumentFormat.detect(w, h))
    }

    @Test
    fun `recognises a receipt by how long it is`() {
        assertEquals(DocumentFormat.RECEIPT, DocumentFormat.detect(1000, 3200))
    }

    @Test
    fun `orientation does not change the answer`() {
        assertEquals(DocumentFormat.detect(1000, 1414), DocumentFormat.detect(1414, 1000))
        assertEquals(DocumentFormat.A4, DocumentFormat.detect(1414, 1000))
    }

    /**
     * The one that matters. An A4 page measured a little short must not come
     * back as a passport, because snapping it to ID-3 would squeeze the page.
     */
    @Test
    fun `a slightly mismeasured a4 is still a4`() {
        listOf(-0.02f, -0.01f, 0.01f, 0.02f).forEach { error ->
            val (w, h) = sizeFor(1.4142f, error = error)
            assertEquals(
                "A4 off by $error was misread",
                DocumentFormat.A4,
                DocumentFormat.detect(w, h)
            )
        }
    }

    @Test
    fun `a shape matching nothing is left alone`() {
        // Ratio 1.8: between an ID card and a receipt, and not near either.
        assertEquals(DocumentFormat.FREEFORM, DocumentFormat.detect(1000, 1800))
        assertTrue(!DocumentFormat.FREEFORM.isKnown)
    }

    @Test
    fun `a square is not a document format`() {
        assertEquals(DocumentFormat.FREEFORM, DocumentFormat.detect(1000, 1000))
    }

    @Test
    fun `degenerate input does not crash or claim a format`() {
        assertEquals(DocumentFormat.FREEFORM, DocumentFormat.detect(0, 0))
        assertEquals(DocumentFormat.FREEFORM, DocumentFormat.detect(-5, 100))
    }

    @Test
    fun `snapping keeps the orientation it was given`() {
        val portrait = DocumentFormat.targetSize(DocumentFormat.A4, 1000, 1414)
        assertNotNull(portrait)
        assertTrue("portrait in, portrait out", portrait!!.second > portrait.first)

        val landscape = DocumentFormat.targetSize(DocumentFormat.A4, 1414, 1000)
        assertNotNull(landscape)
        assertTrue("landscape in, landscape out", landscape!!.first > landscape.second)
    }

    @Test
    fun `snapping produces the format's exact ratio, not the measured one`() {
        // Deliberately off: 1.38 instead of 1.4142.
        val snapped = DocumentFormat.targetSize(DocumentFormat.A4, 1000, 1380)
        assertNotNull(snapped)
        val ratio = snapped!!.second.toFloat() / snapped.first
        assertEquals(1.4142f, ratio, 0.01f)
        assertNotEquals(1.38f, ratio, 0.005f)
    }

    @Test
    fun `freeform has nothing to snap to`() {
        assertNull(DocumentFormat.targetSize(DocumentFormat.FREEFORM, 1000, 1800))
    }

    /** A till receipt has no standard length, so there is nothing to snap it to. */
    @Test
    fun `a receipt is recognised but not snapped`() {
        assertTrue(DocumentFormat.RECEIPT.isKnown)
        assertNull(DocumentFormat.targetSize(DocumentFormat.RECEIPT, 1000, 3200))
    }

    /**
     * Snapping upwards would invent pixels: no more detail, a larger file. A
     * page photographed small stays small, at the right shape.
     */
    @Test
    fun `snapping never upscales`() {
        val snapped = DocumentFormat.targetSize(DocumentFormat.A4, 400, 560)
        assertNotNull(snapped)
        assertTrue("was ${snapped!!.first} x ${snapped.second}", snapped.second <= 560)
        assertEquals(1.4142f, snapped.second.toFloat() / snapped.first, 0.02f)
    }
}
