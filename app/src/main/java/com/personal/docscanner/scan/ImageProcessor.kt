package com.personal.docscanner.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import com.personal.docscanner.data.model.PageFilter
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc

/**
 * Turns a raw photo into a page: straighten it, then make it read like a scan.
 */
object ImageProcessor {

    /**
     * Warps the region bounded by [quad] into a straight-on rectangle.
     * Returns the input unchanged if OpenCV is unavailable.
     */
    /**
     * Longest edge of a processed page.
     *
     * Everything downstream — the filter chain, the JPEG on disk, the PDF — works
     * on this, and each OpenCV step holds another copy of it in memory. At 2600
     * pixels a page is about 220 dpi across A4, comfortably more than the 2000
     * the PDF export uses, and the whole chain stays inside a few tens of
     * megabytes.
     *
     * The size matters because of how failure behaves here: every operation in
     * this file catches Throwable and hands back the *source*. That is the right
     * shape — a page that could not be enhanced is better than no page — but it
     * means an out-of-memory error surfaces as a page that is silently
     * uncropped and unenhanced, with nothing anywhere saying why.
     */
    private const val MAX_PAGE_EDGE = 2600

    private fun bounded(width: Int, height: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= MAX_PAGE_EDGE) return width to height
        val factor = MAX_PAGE_EDGE.toDouble() / longest
        return (width * factor).toInt().coerceAtLeast(1) to
            (height * factor).toInt().coerceAtLeast(1)
    }

    /** Shrinks a bitmap to [MAX_PAGE_EDGE] on its long edge, if it is over. */
    fun boundForProcessing(source: Bitmap): Bitmap {
        val (width, height) = bounded(source.width, source.height)
        if (width == source.width && height == source.height) return source
        return runCatching {
            Bitmap.createScaledBitmap(source, width, height, true)
        }.getOrDefault(source)
    }

    fun warp(source: Bitmap, quad: Quad, snapTo: DocumentFormat? = null): Bitmap {
        if (!OpenCvLoader.ensureLoaded()) return source

        val src = Mat()
        val dst = Mat()
        var srcPoints: MatOfPoint2f? = null
        var dstPoints: MatOfPoint2f? = null
        var transform: Mat? = null

        return try {
            Utils.bitmapToMat(source, src)

            // The measured quad is a couple of percent off even after a good
            // detection, so when the page matches a known format we warp
            // straight to that format's exact ratio instead of the measurement.
            val measuredWidth = quad.outputWidth
            val measuredHeight = quad.outputHeight
            val format = snapTo ?: DocumentFormat.detect(measuredWidth, measuredHeight)
            val snapped = DocumentFormat.targetSize(format, measuredWidth, measuredHeight)
            val (width, height) = bounded(
                snapped?.first ?: measuredWidth,
                snapped?.second ?: measuredHeight
            )

            srcPoints = MatOfPoint2f(
                Point(quad.topLeft.x.toDouble(), quad.topLeft.y.toDouble()),
                Point(quad.topRight.x.toDouble(), quad.topRight.y.toDouble()),
                Point(quad.bottomRight.x.toDouble(), quad.bottomRight.y.toDouble()),
                Point(quad.bottomLeft.x.toDouble(), quad.bottomLeft.y.toDouble())
            )
            dstPoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(width - 1.0, 0.0),
                Point(width - 1.0, height - 1.0),
                Point(0.0, height - 1.0)
            )

            transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            Imgproc.warpPerspective(
                src, dst, transform, Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_CUBIC
            )

            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .also { Utils.matToBitmap(dst, it) }
        } catch (t: Throwable) {
            source
        } finally {
            src.release()
            dst.release()
            srcPoints?.release()
            dstPoints?.release()
            transform?.release()
        }
    }

    /**
     * Applies a look to an already-straightened page.
     *
     * @param brightness -100..100, 0 is untouched
     * @param contrast   -100..100, 0 is untouched
     */
    fun applyFilter(
        source: Bitmap,
        filter: PageFilter,
        brightness: Int = 0,
        contrast: Int = 0
    ): Bitmap {
        if (filter == PageFilter.ORIGINAL && brightness == 0 && contrast == 0) return source
        if (!OpenCvLoader.ensureLoaded()) return source

        val src = Mat()
        val work = Mat()
        return try {
            Utils.bitmapToMat(source, src)
            Imgproc.cvtColor(src, work, Imgproc.COLOR_RGBA2RGB)

            val filtered = when (filter) {
                PageFilter.AUTO -> AutoEnhance.apply(work)
                PageFilter.ORIGINAL -> work.clone()
                PageFilter.MAGIC_COLOR -> magicColor(work)
                PageFilter.GRAYSCALE -> grayscale(work)
                PageFilter.BLACK_WHITE -> blackAndWhite(work)
                PageFilter.LIGHTEN -> lighten(work)
            }

            val adjusted = applyBrightnessContrast(filtered, brightness, contrast)
            if (adjusted !== filtered) filtered.release()

            val out = Mat()
            Imgproc.cvtColor(adjusted, out, Imgproc.COLOR_RGB2RGBA)
            adjusted.release()

            Bitmap.createBitmap(out.width(), out.height(), Bitmap.Config.ARGB_8888)
                .also { Utils.matToBitmap(out, it) }
                .also { out.release() }
        } catch (t: Throwable) {
            source
        } finally {
            src.release()
            work.release()
        }
    }

    // ------------------------------------------------------------- the looks

    /**
     * The "colour document" look: flatten uneven lighting so the paper reads as
     * white, then put the saturation back so stamps and signatures stay visible.
     *
     * Dividing by a heavily-blurred copy of the image is what removes the
     * shadow gradient a phone inevitably casts across a page.
     */
    private fun magicColor(rgb: Mat): Mat {
        val gray = Mat()
        val background = Mat()
        val result = Mat()
        return try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)

            val blurSize = (maxOf(rgb.width(), rgb.height()) / 12).let { if (it % 2 == 0) it + 1 else it }
                .coerceAtLeast(21)
            Imgproc.GaussianBlur(gray, background, Size(blurSize.toDouble(), blurSize.toDouble()), 0.0)

            // Re-apply the illumination correction to each colour channel.
            val channels = ArrayList<Mat>()
            Core.split(rgb, channels)
            val corrected = ArrayList<Mat>(3)
            channels.forEach { channel ->
                val c = Mat()
                Core.divide(channel, background, c, 255.0)
                corrected.add(c)
                channel.release()
            }
            Core.merge(corrected, result)
            corrected.forEach { it.release() }

            // A gentle S-curve: whites go to paper-white, mid-tones keep their colour.
            val boosted = Mat()
            result.convertTo(boosted, -1, 1.15, -12.0)
            result.release()
            boosted
        } finally {
            gray.release()
            background.release()
        }
    }

    private fun grayscale(rgb: Mat): Mat {
        val gray = Mat()
        val out = Mat()
        return try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            val clahe: CLAHE = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            val equalized = Mat()
            clahe.apply(gray, equalized)
            Imgproc.cvtColor(equalized, out, Imgproc.COLOR_GRAY2RGB)
            equalized.release()
            out
        } finally {
            gray.release()
        }
    }

    /**
     * Hard black-on-white, the look you want for text you are going to OCR or print.
     * Adaptive thresholding beats a global one here because the whole point is that
     * one corner of the photo is darker than the other.
     */
    private fun blackAndWhite(rgb: Mat): Mat {
        val gray = Mat()
        val denoised = Mat()
        val binary = Mat()
        val out = Mat()
        return try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.medianBlur(gray, denoised, 3)

            val block = (maxOf(rgb.width(), rgb.height()) / 40).let { if (it % 2 == 0) it + 1 else it }
                .coerceIn(11, 51)
            Imgproc.adaptiveThreshold(
                denoised, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                block, 12.0
            )
            Imgproc.cvtColor(binary, out, Imgproc.COLOR_GRAY2RGB)
            out
        } finally {
            gray.release()
            denoised.release()
            binary.release()
        }
    }

    /** For faint pencil or a receipt printed on thermal paper. */
    private fun lighten(rgb: Mat): Mat {
        val out = Mat()
        rgb.convertTo(out, -1, 1.35, 25.0)
        return out
    }

    private fun applyBrightnessContrast(src: Mat, brightness: Int, contrast: Int): Mat {
        if (brightness == 0 && contrast == 0) return src
        // contrast -100..100 maps to a gain of 0.5x..2.0x
        val alpha = if (contrast >= 0) 1.0 + contrast / 100.0 else 1.0 + contrast / 200.0
        val beta = brightness.toDouble() * 1.2
        val out = Mat()
        src.convertTo(out, -1, alpha, beta)
        return out
    }

    // ------------------------------------------------------------- utilities

    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** Downscales so neither edge exceeds [maxEdge]; returns the input if already small. */
    fun limitSize(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /**
     * Sharpens text a little before OCR. An unsharp mask recovers strokes that
     * JPEG compression softened, which measurably helps Tesseract on Arabic.
     */
    fun sharpenForOcr(source: Bitmap): Bitmap {
        if (!OpenCvLoader.ensureLoaded()) return source
        val src = Mat()
        val gray = Mat()
        val blurred = Mat()
        val sharp = Mat()
        val out = Mat()
        return try {
            Utils.bitmapToMat(source, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(0.0, 0.0), 3.0)
            Core.addWeighted(gray, 1.5, blurred, -0.5, 0.0, sharp)
            Imgproc.cvtColor(sharp, out, Imgproc.COLOR_GRAY2RGBA)
            Bitmap.createBitmap(out.width(), out.height(), Bitmap.Config.ARGB_8888)
                .also { Utils.matToBitmap(out, it) }
        } catch (t: Throwable) {
            source
        } finally {
            listOf(src, gray, blurred, sharp, out).forEach { it.release() }
        }
    }

    @Suppress("unused")
    private fun blank(width: Int, height: Int): Mat =
        Mat(height, width, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
}
