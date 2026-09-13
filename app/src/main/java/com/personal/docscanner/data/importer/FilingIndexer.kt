package com.personal.docscanner.data.importer

import com.personal.docscanner.data.repo.DocumentRepository

/**
 * Fills in a document's save fields from where it sits in the folder tree.
 *
 * An imported library already carries its index in its own structure — the
 * client is a folder, the case is the folder inside it, and the documents are
 * the files inside that. Reading it back out means an import of two hundred
 * files arrives searchable, instead of arriving as two hundred untitled
 * documents that would have to be filled in by hand.
 *
 * The mapping is read from the bottom up:
 *
 *     … / أحمد محمود / قضية 1234 لسنة 2019 / صحيفة دعوى.pdf
 *          ^ client     ^ case number/year    ^ document name
 *
 * so it lands correctly whether the folder that was picked is one client or the
 * whole library. A file sitting directly in the picked folder has no case above
 * it, so that folder is taken as the client and the case is left blank rather
 * than guessed.
 */
class FilingIndexer private constructor(
    private val repo: DocumentRepository,
    private val clientFieldId: String?,
    private val caseFieldId: String?,
    private val yearFieldId: String?
) {

    /** True when at least one field to write into still exists. */
    val enabled: Boolean
        get() = clientFieldId != null || caseFieldId != null || yearFieldId != null

    data class Filing(val client: String, val caseNumber: String, val year: String)

    suspend fun apply(documentId: String, filing: Filing) {
        if (!enabled) return
        clientFieldId?.let { set(documentId, it, filing.client) }
        caseFieldId?.let { set(documentId, it, filing.caseNumber) }
        yearFieldId?.let { set(documentId, it, filing.year) }
    }

    private suspend fun set(documentId: String, fieldId: String, value: String) {
        val trimmed = value.trim()
        // An empty value would clear whatever the field already holds; leaving
        // it alone is the right move when the tree simply does not say.
        if (trimmed.isEmpty()) return
        repo.setFieldValue(documentId, fieldId, trimmed)
    }

    companion object {

        /**
         * @param chain folder names from the picked root down to the folder the
         *   document sits in.
         */
        fun filingFor(chain: List<String>): Filing {
            val names = chain.map { it.trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) return Filing("", "", "")

            val caseName = if (names.size >= 2) names.last() else ""
            val client = if (names.size >= 2) names[names.size - 2] else names.last()
            return Filing(client = client, caseNumber = caseName, year = yearIn(caseName))
        }

        /**
         * Resolves which field each part of the path should be written into.
         *
         * The seeded fields are matched by their stable ids first, so a renamed
         * "اسم العميل" still receives the client. Name matching is the fallback
         * for fields the user created themselves, and a field that has been
         * deleted simply drops out — importing must not resurrect it.
         */
        suspend fun create(repo: DocumentRepository): FilingIndexer {
            val defs = runCatching { repo.fieldDefsOnce() }.getOrDefault(emptyList())

            fun pick(seedId: String, vararg keywords: String): String? {
                defs.firstOrNull { it.id == seedId }?.let { return it.id }
                return defs.firstOrNull { def ->
                    keywords.any { def.name.contains(it) }
                }?.id
            }

            return FilingIndexer(
                repo = repo,
                clientFieldId = pick("seed_client", "العميل", "الموكل"),
                caseFieldId = pick("seed_case_no", "رقم القضية", "رقم الملف", "رقم الدعوى"),
                yearFieldId = pick("seed_case_year", "السنة")
            )
        }

        /** A four-digit year anywhere in the folder name, e.g. "جنح 4412 لسنة 2021". */
        private val YEAR = Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)")

        /** Arabic-Indic digits are common in folder names typed on a phone. */
        private const val ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"
        private const val EXTENDED_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

        private fun yearIn(text: String): String =
            YEAR.find(westernDigits(text))?.value.orEmpty()

        private fun westernDigits(text: String): String = buildString {
            text.forEach { ch ->
                val arabic = ARABIC_DIGITS.indexOf(ch)
                val extended = EXTENDED_DIGITS.indexOf(ch)
                append(
                    when {
                        arabic >= 0 -> '0' + arabic
                        extended >= 0 -> '0' + extended
                        else -> ch
                    }
                )
            }
        }
    }
}
