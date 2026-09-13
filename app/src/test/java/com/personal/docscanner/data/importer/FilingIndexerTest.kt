package com.personal.docscanner.data.importer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mapping from an imported folder path onto client / case / year. Getting
 * this wrong files two hundred documents under the wrong client, which is worse
 * than not indexing them at all.
 */
class FilingIndexerTest {

    @Test
    fun `reads client and case from the two nearest folders`() {
        val filing = FilingIndexer.filingFor(listOf("أحمد محمود", "قضية 1234"))
        assertEquals("أحمد محمود", filing.client)
        assertEquals("قضية 1234", filing.caseNumber)
    }

    /**
     * Reading bottom-up is what lets the same code cope with either choice at
     * the picker: a single client folder, or the whole exported library.
     */
    @Test
    fun `ignores how deep the tree is above the client`() {
        val filing = FilingIndexer.filingFor(
            listOf("CamScanner", "قضايا العملاء", "أحمد محمود", "جنح 4412")
        )
        assertEquals("أحمد محمود", filing.client)
        assertEquals("جنح 4412", filing.caseNumber)
    }

    @Test
    fun `a lone folder is the client, and the case is left blank`() {
        val filing = FilingIndexer.filingFor(listOf("أحمد محمود"))
        assertEquals("أحمد محمود", filing.client)
        assertEquals("", filing.caseNumber)
    }

    @Test
    fun `an empty path fills nothing rather than guessing`() {
        val filing = FilingIndexer.filingFor(emptyList())
        assertEquals("", filing.client)
        assertEquals("", filing.caseNumber)
        assertEquals("", filing.year)
    }

    @Test
    fun `blank folder names are skipped`() {
        val filing = FilingIndexer.filingFor(listOf("  ", "أحمد محمود", "", "قضية 9"))
        assertEquals("أحمد محمود", filing.client)
        assertEquals("قضية 9", filing.caseNumber)
    }

    @Test
    fun `pulls a year out of the case folder`() {
        assertEquals("2019", FilingIndexer.filingFor(listOf("عميل", "قضية 1234 لسنة 2019")).year)
    }

    @Test
    fun `understands a year written in arabic-indic digits`() {
        assertEquals("2021", FilingIndexer.filingFor(listOf("عميل", "جنح ٤٤١٢ لسنة ٢٠٢١")).year)
    }

    @Test
    fun `leaves the year blank when there is no year to find`() {
        assertEquals("", FilingIndexer.filingFor(listOf("عميل", "مستندات عامة")).year)
    }

    /**
     * A case number is not a year. Only a four-digit run in the 1900s or 2000s
     * counts, so "قضية 1234" does not silently become the year 1234.
     */
    @Test
    fun `does not mistake a case number for a year`() {
        assertEquals("", FilingIndexer.filingFor(listOf("عميل", "قضية 1234")).year)
        assertEquals("", FilingIndexer.filingFor(listOf("عميل", "رقم 20211")).year)
    }
}
