package com.personal.docscanner.scan

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizerTest {

    @Test
    fun `too short to summarize returns null`() {
        assertNull(Summarizer.summarize("جملة واحدة فقط."))
        assertNull(Summarizer.summarize(""))
    }

    @Test
    fun `keeps only sentences already in the source`() {
        val text = """
            هذا عقد إيجار مبرم بين الطرف الأول والطرف الثاني.
            يلتزم الطرف الأول بتسليم الوحدة السكنية في الموعد المتفق عليه.
            تكون مدة العقد سنة واحدة قابلة للتجديد.
            يلتزم الطرف الثاني بسداد الإيجار الشهري في أول كل شهر.
            في حالة الإخلال بأي بند يحق للطرف المتضرر فسخ العقد.
            وقع الطرفان على هذا العقد في نهاية الجلسة.
        """.trimIndent()

        val summary = Summarizer.summarize(text)
        assertTrue(summary != null)
        val sourceSentences = text.split("\n").map { it.trim() }
        summary!!.split("\n").forEach { line ->
            assertTrue("unexpected sentence not in source: $line", line in sourceSentences)
        }
    }

    @Test
    fun `never grows longer than the source`() {
        val text = (1..20).joinToString(" ") { "هذه الجملة رقم $it تتحدث عن موضوع القضية." }
        val summary = Summarizer.summarize(text)
        assertTrue(summary != null)
        assertTrue(summary!!.length <= text.length)
    }
}
