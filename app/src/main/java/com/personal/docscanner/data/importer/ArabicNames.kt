package com.personal.docscanner.data.importer

/**
 * Matching Arabic personal names written by hand, across years, by one person
 * who was not being careful about spelling.
 *
 * The same client is "أحمد محمود على" in one file name and "احمد محمود علي" in
 * the next: the hamza left off, ى for ي, a stray dash, two spaces. None of that
 * changes who the person is, and all of it defeats a string comparison.
 *
 * Used to file a power of attorney or an identity document — papers that belong
 * to the client rather than to any one case — into that client's folder on
 * import. Getting it wrong puts a client's power of attorney in another
 * client's folder, so every rule here errs towards doing nothing.
 */
object ArabicNames {

    /**
     * Reduces a name to the letters that identify the person.
     *
     * Deliberately lossy. Hamza carriers all collapse to ا, ى to ي, ة to ه —
     * these are the variations that carry no information about who is meant and
     * that people write inconsistently.
     */
    fun normalize(raw: String): String {
        val out = StringBuilder(raw.length)
        raw.forEach { ch ->
            when {
                ch in DIACRITICS -> Unit
                ch == TATWEEL -> Unit
                ch in "أإآٱا" -> out.append('ا')
                ch == 'ى' -> out.append('ي')
                ch == 'ة' -> out.append('ه')
                ch == 'ؤ' -> out.append('و')
                ch == 'ئ' -> out.append('ي')
                ch.isLetterOrDigit() -> out.append(ch.lowercaseChar())
                // Everything else — dashes, underscores, dots, brackets — is a
                // separator. File names are full of them and they mean nothing.
                else -> out.append(' ')
            }
        }
        return out.toString().trim().replace(WHITESPACE, " ")
    }

    fun tokens(raw: String): List<String> =
        normalize(raw).split(' ').filter { it.isNotEmpty() }

    /**
     * True when [nameTokens] appears inside [haystackTokens] as a run of whole
     * words.
     *
     * Whole words and contiguous on purpose. "أحمد محمود" must match
     * "توكيل أحمد محمود" but not "أحمد سعيد محمود", who is a different person,
     * and not "محمد" inside "محمود".
     */
    fun containsRun(haystackTokens: List<String>, nameTokens: List<String>): Boolean {
        if (nameTokens.isEmpty() || nameTokens.size > haystackTokens.size) return false
        for (start in 0..haystackTokens.size - nameTokens.size) {
            var matched = true
            for (i in nameTokens.indices) {
                if (haystackTokens[start + i] != nameTokens[i]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    /**
     * Picks the client whose name appears in [text], or null when the answer is
     * not clear enough to act on.
     *
     * Three refusals, each one a case where a wrong answer costs more than no
     * answer:
     *
     *  - A name of one word is never matched. Half the clients are called أحمد,
     *    and "توكيل أحمد" says nothing about which one.
     *  - When several clients match, the longest wins — "أحمد محمود علي" is
     *    more specific than "أحمد محمود" and is the better reading of a file
     *    that contains both.
     *  - ...unless two of them are equally long, which means the file genuinely
     *    does not say. Then nothing is done.
     */
    fun bestMatch(text: String, candidates: Collection<String>): String? {
        val haystack = tokens(text)
        if (haystack.isEmpty()) return null

        val matches = candidates
            .map { it to tokens(it) }
            .filter { (_, nameTokens) -> nameTokens.size >= MIN_NAME_TOKENS }
            .filter { (_, nameTokens) -> containsRun(haystack, nameTokens) }

        if (matches.isEmpty()) return null

        val longest = matches.maxOf { it.second.size }
        val best = matches.filter { it.second.size == longest }
        return if (best.size == 1) best.first().first else null
    }

    /** One word is not a name in a country where thousands share it. */
    private const val MIN_NAME_TOKENS = 2

    private const val TATWEEL = 'ـ'
    private val DIACRITICS = ('ً'..'ْ') + 'ٰ' + 'ٓ' + 'ٔ' + 'ٕ'
    private val WHITESPACE = Regex("\\s+")
}
