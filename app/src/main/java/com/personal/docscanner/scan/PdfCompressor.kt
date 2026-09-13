package com.personal.docscanner.scan

import com.personal.docscanner.data.model.PdfPageSize
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Squeezes a scanned document under a hard size limit — the kind government
 * e-filing portals impose ("attachment must not exceed 1 MB").
 *
 * The naive approach, dropping JPEG quality until the file fits, destroys text
 * long before it saves much space. This does it in the order that costs the
 * least legibility:
 *
 *  1. drop quality down to a floor that still keeps strokes clean
 *  2. only then reduce resolution, and never below the DPI at which printed
 *     text stops being reliably readable
 *
 * If the target cannot be met without crossing those floors, it stops at the
 * floor and reports the size it actually achieved, rather than silently
 * handing back an unreadable document.
 */
object PdfCompressor {

    /** Limits seen on government upload forms; the user can also type a value. */
    enum class Preset(val labelAr: String, val bytes: Long) {
        KB_300("٣٠٠ ك.ب", 300L * 1024),
        KB_500("٥٠٠ ك.ب", 500L * 1024),
        MB_1("١ ميجا", 1024L * 1024),
        MB_2("٢ ميجا", 2L * 1024 * 1024),
        MB_5("٥ ميجا", 5L * 1024 * 1024)
    }

    data class Result(
        val file: File,
        val bytes: Long,
        val targetBytes: Long,
        val quality: Int,
        val scale: Float,
        /** True when the floors were hit before the target was reached. */
        val hitFloor: Boolean
    ) {
        val metTarget: Boolean get() = bytes <= targetBytes
    }

    /** Below this, JPEG artefacts start eating thin Arabic strokes. */
    private const val MIN_QUALITY = 35
    private const val START_QUALITY = 88

    /** Never shrink a page below roughly 150 dpi worth of pixels. */
    private const val MIN_LONG_EDGE = 1240

    /** Where the search starts, matching what a plain export produces. */
    private const val MAX_LONG_EDGE = PdfExporter.DEFAULT_MAX_EDGE

    private const val MAX_SCALE_STEPS = 5

    /**
     * Builds a PDF from [pageFiles] that is at most [targetBytes].
     *
     * @param staging a scratch file to write into; it is overwritten each attempt
     */
    fun compressToTarget(
        pageFiles: List<File>,
        staging: File,
        targetBytes: Long,
        pageSize: PdfPageSize = PdfPageSize.A4,
        onAttempt: (Int) -> Unit = {}
    ): Result {
        require(pageFiles.isNotEmpty()) { "nothing to compress" }

        var attempt = 0

        // Pass 1: quality only, binary-searched. Each probe is a full PDF build,
        // so a search beats a linear walk — six probes cover the whole range.
        var low = MIN_QUALITY
        var high = START_QUALITY
        var bestQuality = -1

        while (low <= high) {
            val quality = (low + high) / 2
            onAttempt(++attempt)
            val size = build(pageFiles, staging, quality, MAX_LONG_EDGE, pageSize)
            if (size <= targetBytes) {
                bestQuality = quality
                low = quality + 1
            } else {
                high = quality - 1
            }
        }
        if (bestQuality >= 0) {
            // Re-emit at the best quality found, since the last probe may have been a miss.
            val size = build(pageFiles, staging, bestQuality, MAX_LONG_EDGE, pageSize)
            return Result(staging, size, targetBytes, bestQuality, 1f, hitFloor = false)
        }

        // Pass 2: still too big at minimum quality — start shrinking. Size scales
        // roughly with pixel count, so scaling by sqrt(target/actual) converges
        // in one or two steps instead of creeping down by 10% at a time.
        var edge = MAX_LONG_EDGE
        var lastSize = build(pageFiles, staging, MIN_QUALITY, edge, pageSize)

        var step = 0
        // Stops on any of three conditions: the target is met, the resolution
        // floor is reached, or the step budget runs out. `repeat` with an early
        // `return@repeat` would only skip an iteration, so once the floor was hit
        // it would go on rebuilding the identical PDF several more times — on a
        // twenty-page file that is several seconds of work for no change.
        while (step < MAX_SCALE_STEPS && lastSize > targetBytes && edge > MIN_LONG_EDGE) {
            step++
            val factor = sqrt(targetBytes.toDouble() / lastSize.toDouble()).toFloat()
            edge = (edge * factor.coerceIn(0.5f, 0.92f)).roundToInt().coerceAtLeast(MIN_LONG_EDGE)
            onAttempt(++attempt)
            lastSize = build(pageFiles, staging, MIN_QUALITY, edge, pageSize)
        }

        return Result(
            file = staging,
            bytes = lastSize,
            targetBytes = targetBytes,
            quality = MIN_QUALITY,
            scale = edge.toFloat() / MAX_LONG_EDGE,
            hitFloor = lastSize > targetBytes
        )
    }

    /** Renders one candidate PDF and returns its size in bytes. */
    private fun build(
        pageFiles: List<File>,
        staging: File,
        quality: Int,
        maxEdge: Int,
        pageSize: PdfPageSize
    ): Long {
        PdfExporter.export(pageFiles, staging, pageSize, quality, maxEdge)
        return staging.length()
    }

    fun humanTarget(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / 1024} KB"
    }
}
