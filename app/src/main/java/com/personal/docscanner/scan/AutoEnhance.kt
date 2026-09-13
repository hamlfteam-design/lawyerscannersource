package com.personal.docscanner.scan

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * The one-tap "make this look like a scan" pass.
 *
 * Four things go wrong when a phone photographs paper, and each needs a
 * different correction, applied in this order:
 *
 *  1. **Shadow** — the phone or the hand casts a gradient across the page.
 *     Removed by dividing the image by a heavily blurred copy of itself, which
 *     is an estimate of the illumination field. This has to come first, because
 *     every later step assumes even lighting.
 *  2. **Colour cast** — indoor light is rarely white. Fixed with grey-world
 *     white balance on the already-flattened image.
 *  3. **Contrast** — paper should be white, ink should be black. A percentile
 *     stretch is used rather than a fixed curve so a faint carbon copy and a
 *     crisp laser print both land in the same place.
 *  4. **Softness** — autofocus and JPEG both blur fine strokes. An unsharp mask
 *     puts the edges back without amplifying the noise a sharpen filter would.
 */
object AutoEnhance {

    /**
     * @param rgb an 8-bit 3-channel image; not modified
     * @return a new Mat the caller owns
     */
    fun apply(rgb: Mat): Mat {
        val deshadowed = removeShadow(rgb)
        val balanced = try {
            whiteBalance(deshadowed)
        } finally {
            deshadowed.release()
        }
        val stretched = try {
            stretchContrast(balanced)
        } finally {
            balanced.release()
        }
        return try {
            unsharpMask(stretched)
        } finally {
            stretched.release()
        }
    }

    /**
     * Divides out the illumination field.
     *
     * The blur radius has to be large relative to the text — around a twelfth of
     * the page — or the letters themselves end up in the "background" estimate
     * and get erased along with the shadow.
     */
    fun removeShadow(rgb: Mat): Mat {
        val channels = ArrayList<Mat>()
        val corrected = ArrayList<Mat>(3)
        val result = Mat()

        val kernel = (maxOf(rgb.width(), rgb.height()) / 12)
            .let { if (it % 2 == 0) it + 1 else it }
            .coerceAtLeast(31)

        try {
            Core.split(rgb, channels)
            channels.forEach { channel ->
                val background = Mat()
                val divided = Mat()
                try {
                    // A dilate before the blur removes dark text from the
                    // background estimate, so thick ink does not leave a halo.
                    val dilated = Mat()
                    val structuring = Imgproc.getStructuringElement(
                        Imgproc.MORPH_RECT, Size(7.0, 7.0)
                    )
                    Imgproc.dilate(channel, dilated, structuring)
                    structuring.release()

                    Imgproc.medianBlur(dilated, background, MEDIAN_K)
                    dilated.release()
                    Imgproc.GaussianBlur(
                        background, background,
                        Size(kernel.toDouble(), kernel.toDouble()), 0.0
                    )

                    Core.divide(channel, background, divided, 255.0)
                    corrected.add(divided)
                } finally {
                    background.release()
                }
            }
            Core.merge(corrected, result)
            return result
        } finally {
            channels.forEach { it.release() }
            corrected.forEach { it.release() }
        }
    }

    /**
     * Grey-world white balance: assume the average of the whole page should be
     * neutral, and scale each channel to make it so. On a document — mostly
     * white paper — this is a very good assumption.
     */
    fun whiteBalance(rgb: Mat): Mat {
        val channels = ArrayList<Mat>()
        val scaled = ArrayList<Mat>(3)
        val result = Mat()
        try {
            Core.split(rgb, channels)
            val means = channels.map { Core.mean(it).`val`[0] }
            val target = means.average()
            if (target <= 0.0) {
                rgb.copyTo(result)
                return result
            }
            channels.forEachIndexed { index, channel ->
                val mean = means[index]
                val gain = if (mean > 1.0) (target / mean).coerceIn(0.7, 1.4) else 1.0
                val out = Mat()
                channel.convertTo(out, -1, gain, 0.0)
                scaled.add(out)
            }
            Core.merge(scaled, result)
            return result
        } finally {
            channels.forEach { it.release() }
            scaled.forEach { it.release() }
        }
    }

    /**
     * Percentile contrast stretch driven by the luminance histogram.
     *
     * Clipping the darkest 0.5% and lightest 1% before stretching is what makes
     * this robust: a single black speck or a blown highlight would otherwise
     * anchor the range and leave the page grey.
     */
    fun stretchContrast(rgb: Mat): Mat {
        val gray = Mat()
        val result = Mat()
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            val (low, high) = percentiles(gray, 0.005, 0.99)
            if (high <= low) {
                rgb.copyTo(result)
                return result
            }
            val alpha = 255.0 / (high - low)
            val beta = -low * alpha
            rgb.convertTo(result, -1, alpha, beta)
            return result
        } finally {
            gray.release()
        }
    }

    private fun percentiles(gray: Mat, lowP: Double, highP: Double): Pair<Double, Double> {
        val hist = Mat()
        return try {
            Imgproc.calcHist(
                listOf(gray), org.opencv.core.MatOfInt(0), Mat(), hist,
                org.opencv.core.MatOfInt(256),
                org.opencv.core.MatOfFloat(0f, 256f)
            )
            val total = gray.total().toDouble()
            var cumulative = 0.0
            var low = 0.0
            var high = 255.0
            var lowFound = false
            for (i in 0 until 256) {
                cumulative += hist.get(i, 0)?.firstOrNull() ?: 0.0
                val fraction = cumulative / total
                if (!lowFound && fraction >= lowP) {
                    low = i.toDouble()
                    lowFound = true
                }
                if (fraction >= highP) {
                    high = i.toDouble()
                    break
                }
            }
            low to high
        } catch (t: Throwable) {
            0.0 to 255.0
        } finally {
            hist.release()
        }
    }

    /**
     * Unsharp mask. Amount 0.6 is deliberately gentle — anything stronger puts
     * white halos around Arabic diacritics, which reads worse than a slightly
     * soft scan and confuses OCR.
     */
    fun unsharpMask(rgb: Mat, amount: Double = 0.6, radius: Double = 2.0): Mat {
        val blurred = Mat()
        val result = Mat()
        return try {
            Imgproc.GaussianBlur(rgb, blurred, Size(0.0, 0.0), radius)
            Core.addWeighted(rgb, 1.0 + amount, blurred, -amount, 0.0, result)
            result
        } catch (t: Throwable) {
            rgb.copyTo(result)
            result
        } finally {
            blurred.release()
        }
    }

    private const val MEDIAN_K = 21

    @Suppress("unused")
    private fun white(width: Int, height: Int): Mat =
        Mat(height, width, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
}
