package com.personal.docscanner.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filing a power of attorney under the wrong client is the failure that matters
 * here, so most of these tests are about the matcher refusing to answer.
 */
class ArabicNamesTest {

    private val clients = listOf(
        "أحمد محمود توفيق",
        "أحمد محمود",
        "إيهاب محمد صلاح",
        "أميمة سيد قطب",
        "الياس البير الياس",
        "أحمد"
    )

    // ----------------------------------------------------------- normalising

    @Test
    fun `hamza carriers all read as alef`() {
        assertEquals(ArabicNames.normalize("أحمد"), ArabicNames.normalize("احمد"))
        assertEquals(ArabicNames.normalize("إيهاب"), ArabicNames.normalize("ايهاب"))
        assertEquals(ArabicNames.normalize("آمال"), ArabicNames.normalize("امال"))
    }

    @Test
    fun `alef maqsura reads as ya, and ta marbuta as ha`() {
        assertEquals(ArabicNames.normalize("على"), ArabicNames.normalize("علي"))
        assertEquals(ArabicNames.normalize("أميمة"), ArabicNames.normalize("اميمه"))
    }

    @Test
    fun `diacritics and tatweel are ignored`() {
        assertEquals(ArabicNames.normalize("محمود"), ArabicNames.normalize("مَحْمُود"))
        assertEquals(ArabicNames.normalize("محمود"), ArabicNames.normalize("محـــمود"))
    }

    @Test
    fun `punctuation separates rather than joins`() {
        assertEquals(
            listOf("احمد", "محمود"),
            ArabicNames.tokens("أحمد_محمود")
        )
        // Every separator behaves the same way, including the dot before an
        // extension — which therefore becomes a word of its own.
        assertEquals(
            listOf("توكيل", "احمد", "محمود", "pdf"),
            ArabicNames.tokens("  توكيل - أحمد   محمود.pdf  ")
        )
    }

    /**
     * The importer strips ".pdf" before it gets here, but nothing should depend
     * on that: an extension left on is one more word in the haystack, and the
     * client's name is still a run inside it.
     */
    @Test
    fun `a file extension does not affect the match`() {
        assertEquals(
            "أحمد محمود",
            ArabicNames.bestMatch("توكيل أحمد محمود.pdf", clients)
        )
    }

    // -------------------------------------------------------------- matching

    @Test
    fun `finds the client named in a file name`() {
        assertEquals(
            "إيهاب محمد صلاح",
            ArabicNames.bestMatch("توكيل إيهاب محمد صلاح.pdf", clients)
        )
    }

    @Test
    fun `spelling differences do not stop a match`() {
        assertEquals(
            "أميمة سيد قطب",
            ArabicNames.bestMatch("صوره بطاقه اميمه سيد قطب", clients)
        )
    }

    /** The whole point: papers that belong to the client, not to a case. */
    @Test
    fun `matches an identity document and a power of attorney alike`() {
        assertEquals("أحمد محمود", ArabicNames.bestMatch("رقم قومي أحمد محمود", clients))
        assertEquals("أحمد محمود", ArabicNames.bestMatch("2019 توكيل أحمد محمود", clients))
    }

    @Test
    fun `prefers the more specific client when both appear`() {
        assertEquals(
            "أحمد محمود توفيق",
            ArabicNames.bestMatch("توكيل أحمد محمود توفيق 2021", clients)
        )
    }

    // ------------------------------------------------------------- refusals

    @Test
    fun `a one-word name is never matched`() {
        // "أحمد" is in the client list and in the text, and is still refused.
        assertNull(ArabicNames.bestMatch("توكيل أحمد", clients))
    }

    @Test
    fun `words must be whole`() {
        // محمد inside محمود, and a different middle name.
        assertNull(ArabicNames.bestMatch("توكيل أحمد سعيد محمود", clients))
    }

    @Test
    fun `the name must be contiguous`() {
        assertFalse(
            ArabicNames.containsRun(
                ArabicNames.tokens("احمد سعيد محمود"),
                ArabicNames.tokens("احمد محمود")
            )
        )
    }

    @Test
    fun `two equally specific clients mean no answer`() {
        val twins = listOf("سعيد كامل", "كامل سعيد")
        assertNull(ArabicNames.bestMatch("توكيل سعيد كامل سعيد", twins))
    }

    @Test
    fun `a file naming nobody is left alone`() {
        assertNull(ArabicNames.bestMatch("فاتورة كهرباء 2024", clients))
        assertNull(ArabicNames.bestMatch("", clients))
    }

    @Test
    fun `no clients means no match`() {
        assertNull(ArabicNames.bestMatch("توكيل أحمد محمود", emptyList()))
    }

    @Test
    fun `latin names work too`() {
        assertEquals(
            "Export Partners",
            ArabicNames.bestMatch("Contract - EXPORT partners.pdf", listOf("Export Partners"))
        )
    }

    @Test
    fun `a run at the very start or very end still counts`() {
        assertTrue(
            ArabicNames.containsRun(
                ArabicNames.tokens("احمد محمود توكيل"),
                ArabicNames.tokens("احمد محمود")
            )
        )
        assertTrue(
            ArabicNames.containsRun(
                ArabicNames.tokens("توكيل احمد محمود"),
                ArabicNames.tokens("احمد محمود")
            )
        )
    }
}
