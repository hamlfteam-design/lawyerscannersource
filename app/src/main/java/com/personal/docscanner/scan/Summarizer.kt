package com.personal.docscanner.scan

import kotlin.math.ln

/**
 * Offline, on-device document summarization.
 *
 * This is deliberately extractive rather than generative: it picks the most
 * representative sentences already in the text instead of writing new ones.
 * A generative summary needs a language model in the hundreds of megabytes to
 * gigabytes to run locally with no server, which would multiply this app's
 * install size and be slow on the mid-range phones a law office actually
 * hands out. Extractive scoring needs no model file at all, runs in
 * milliseconds, and — because every sentence it returns is copied verbatim
 * from the source — never invents a clause, a date or a name that was not in
 * the original document, which matters more for a contract or a court minute
 * than smoother prose would.
 */
object Summarizer {

    /** Never return more sentences than this, however long the source is. */
    private const val MAX_SENTENCES = 8

    /** Never summarize down to less than this fraction of the source sentences. */
    private const val MIN_FRACTION = 0.2

    /** Below this many sentences there is nothing meaningful to cut. */
    private const val MIN_SENTENCE_COUNT = 4

    private val SENTENCE_SPLIT = Regex("(?<=[.!?؟。\\n])\\s+")
    private val WORD_SPLIT = Regex("[\\s،,.;:!?؟()\\[\\]\"'«»\\-–—]+")

    /** Common function words in both languages, worth nothing toward a sentence's score. */
    private val STOPWORDS = setOf(
        // Arabic
        "من", "إلى", "الى", "عن", "على", "في", "و", "او", "أو", "ثم", "أن", "ان",
        "إن", "الذي", "التي", "الذين", "هذا", "هذه", "ذلك", "تلك", "كان", "كانت",
        "يكون", "لم", "لن", "لا", "ما", "مع", "بعد", "قبل", "بين", "كل", "بعض",
        "هو", "هي", "هم", "أنه", "انه", "أنها", "انها", "قد", "كما", "حيث", "أي",
        "اي", "له", "لها", "لهم", "به", "بها", "هذين", "هذان", "الا", "إلا",
        // English
        "the", "a", "an", "of", "to", "and", "or", "in", "on", "for", "is", "are",
        "was", "were", "be", "this", "that", "these", "those", "it", "as", "by",
        "with", "at", "from", "which", "who", "shall", "will"
    )

    /**
     * Returns up to [MAX_SENTENCES] sentences from [text], in their original
     * order, or null when there is too little text to meaningfully cut down.
     */
    fun summarize(text: String): String? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return null

        val sentences = cleaned.split(SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.size < MIN_SENTENCE_COUNT) return null

        val sentenceWords = sentences.map { tokenize(it) }

        // Frequency across the whole document decides which words are "topical"
        // (repeated, so probably what the document is about) versus incidental.
        val frequency = HashMap<String, Int>()
        sentenceWords.forEach { words -> words.forEach { frequency[it] = (frequency[it] ?: 0) + 1 } }
        if (frequency.isEmpty()) return null

        val docLength = sentences.size.toDouble()
        val scores = DoubleArray(sentences.size) { index ->
            val words = sentenceWords[index]
            if (words.isEmpty()) return@DoubleArray 0.0
            val topicality = words.sumOf { frequency.getValue(it).toDouble() } / words.size
            // A rare, log-scaled boost for the opening and closing sentences: a
            // contract's parties are named in its first line and its
            // signatures/conclusion in its last, neither of which repeats
            // enough words elsewhere to score highly on topicality alone.
            val positionBoost = when (index) {
                0, sentences.lastIndex -> 1.0 + ln(docLength.coerceAtLeast(2.0))
                else -> 1.0
            }
            topicality * positionBoost
        }

        val take = (sentences.size * MIN_FRACTION)
            .toInt()
            .coerceAtLeast(1)
            .coerceAtMost(MAX_SENTENCES)
            .coerceAtMost(sentences.size)

        val chosen = scores.indices
            .sortedByDescending { scores[it] }
            .take(take)
            .sorted()

        return chosen.joinToString("\n") { sentences[it] }
    }

    private fun tokenize(sentence: String): List<String> =
        sentence.lowercase()
            .split(WORD_SPLIT)
            .filter { it.length > 1 && it !in STOPWORDS }
}
