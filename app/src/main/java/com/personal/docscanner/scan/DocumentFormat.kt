package com.personal.docscanner.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Recognises what kind of document was just scanned from the shape of the
 * detected quad, and snaps the output to that format's exact aspect ratio.
 *
 * A phone never photographs a page perfectly square-on, so even after
 * perspective correction the result is off by a percent or two. Snapping to a
 * known format removes that error entirely — an A4 page comes out at exactly
 * 1:√2 instead of 1:1.40, which is what makes a stack of scans print evenly.
 */
enum class DocumentFormat(
    val labelAr: String,
    /** Long edge / short edge. */
    val ratio: Float,
    /** Physical short edge in millimetres, used to pick a sane pixel size. */
    val shortEdgeMm: Float,
    /** How far a measured ratio may stray and still match. */
    val tolerance: Float = 0.05f
) {
    /** ISO 216 A-series: A4, A5, A3 all share 1:√2. */
    A4("A4", 1.4142f, 210f),

    /** US Letter / Legal-ish; common in imported PDFs and forms. */
    LETTER("Letter", 1.2941f, 216f, tolerance = 0.035f),

    /** ISO/IEC 7810 ID-1: national ID, driving licence, bank card. */
    ID_CARD("بطاقة", 1.5857f, 54f, tolerance = 0.06f),

    /** ISO/IEC 7810 ID-3: passport data page. */
    PASSPORT("باسبور", 1.4205f, 88f, tolerance = 0.035f),

    /** Long and narrow — a till receipt. */
    RECEIPT("إيصال", 2.6f, 80f, tolerance = 0.9f),

    /** Nothing matched; keep the measured shape. */
    FREEFORM("حر", 0f, 0f);

    val isKnown: Boolean get() = this != FREEFORM

    companion object {

        /** Reads a stored name back, tolerating one written by an older build. */
        fun parse(name: String?): DocumentFormat? =
            if (name.isNullOrBlank()) null else entries.firstOrNull { it.name == name }

        /**
         * Classifies a quad's warped output.
         *
         * A4 is 1.4142 and an ISO ID-3 passport page is 1.4205 — 0.44% apart.
         * A quad measured off a hand-held photograph carries more error than
         * that even after perspective correction, so **shape cannot tell these
         * two apart**, and a nearest-match rule decides between them on noise.
         * It did: an A4 page measured 0.7% long came back as a passport and was
         * snapped to a passport's proportions.
         *
         * Where the two overlap, A4 wins. The errors are not symmetric. This is
         * an app for case files, so the band is nearly always A4; and treating a
         * passport as A4 shifts its ratio by 0.44%, which no eye will catch,
         * while treating a contract page as a passport visibly squeezes every
         * page of the bundle.
         */
        fun detect(widthPx: Int, heightPx: Int): DocumentFormat {
            if (widthPx <= 0 || heightPx <= 0) return FREEFORM
            val ratio = max(widthPx, heightPx).toFloat() / min(widthPx, heightPx).toFloat()

            // RECEIPT first: its huge tolerance would otherwise swallow everything.
            if (ratio >= 2.0f) return RECEIPT

            val matches = listOf(ID_CARD, LETTER, PASSPORT, A4)
                .filter { abs(ratio - it.ratio) <= it.tolerance }

            if (A4 in matches) return A4
            return matches.minByOrNull { abs(ratio - it.ratio) } ?: FREEFORM
        }

        /**
         * Output pixel dimensions for [format] at [dpi], preserving the
         * orientation implied by [widthPx] / [heightPx].
         *
         * 300 dpi is the floor most government e-filing portals accept and is
         * comfortably enough for OCR; going higher only inflates the file.
         */
        fun targetSize(
            format: DocumentFormat,
            widthPx: Int,
            heightPx: Int,
            dpi: Int = 300
        ): Pair<Int, Int>? {
            if (!format.isKnown || format == RECEIPT) return null

            val shortPx = (format.shortEdgeMm / MM_PER_INCH * dpi).toInt()
            val longPx = (shortPx * format.ratio).toInt()

            // Never upscale: inventing pixels adds no detail and costs file size.
            val currentLong = max(widthPx, heightPx)
            val currentShort = min(widthPx, heightPx)
            if (longPx > currentLong || shortPx > currentShort) {
                val scale = min(
                    currentLong.toFloat() / longPx,
                    currentShort.toFloat() / shortPx
                )
                val scaledShort = (shortPx * scale).toInt().coerceAtLeast(1)
                val scaledLong = (scaledShort * format.ratio).toInt().coerceAtLeast(1)
                return orient(widthPx, heightPx, scaledLong, scaledShort)
            }
            return orient(widthPx, heightPx, longPx, shortPx)
        }

        private fun orient(
            widthPx: Int,
            heightPx: Int,
            longPx: Int,
            shortPx: Int
        ): Pair<Int, Int> =
            if (widthPx >= heightPx) longPx to shortPx else shortPx to longPx

        private const val MM_PER_INCH = 25.4f
    }
}
