package com.personal.docscanner.scan

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Finds the page boundary in a photo.
 *
 * Detection runs on a shrunk copy (small images are much faster and the page
 * outline survives the shrink perfectly well), and the answer is scaled back to
 * the original pixel coordinates before it is returned.
 *
 * Three passes look for the page, because no single one of them is reliable:
 *
 *  - **Edges.** Canny, with its two bounds derived from an Otsu threshold so the
 *    same code copes with a dim room and with glare. Works when the page has a
 *    crisp outline all the way round.
 *  - **Bright region.** The page is usually the brightest large thing in frame;
 *    thresholding for it finds the page even when its outline is broken by a
 *    clip, a shadow or a finger, which is exactly where the edge pass gives up.
 *  - **Dark region.** The same idea inverted, for a page photographed on a white
 *    desk, where the page is the *darker* of the two.
 *
 * Every candidate is scored and the best one wins, rather than the first one
 * found — a partial match from the edge pass should not beat a clean match from
 * the region pass just because it was computed first.
 */
object EdgeDetector {

    /** Detection runs on an image no wider/taller than this, in pixels. */
    private const val WORK_EDGE = 700.0

    /** A candidate must cover at least this share of the frame to count as a page. */
    private const val MIN_AREA_RATIO = 0.08

    /**
     * Minimum area share used instead of [MIN_AREA_RATIO] when the caller knows
     * it is looking for something card-sized. An ID card is commonly shot with
     * a lot of desk around it — nowhere near the 8% floor a full page needs —
     * so without this the region passes never even produce the card as a
     * candidate, and whatever large bright/dark shape *does* clear the floor
     * (the desk, the table edge) wins by default.
     */
    private const val MIN_AREA_RATIO_SMALL_SUBJECT = 0.02

    /**
     * How far a candidate's measured long/short ratio may stray from
     * [expectedRatio] and still be considered, as a fraction of that ratio.
     * Wide enough to absorb perspective skew on a hand-held shot, narrow
     * enough that a table or a whole desk — which rarely happens to share an
     * ID card's 1.59:1 proportions — is rejected rather than just outscored.
     */
    private const val EXPECTED_RATIO_TOLERANCE = 0.35

    /**
     * ...and at most this much. A page held far enough back to photograph never
     * fills the whole frame, so anything larger is the frame border itself, or
     * the background with the page punched out of it.
     */
    private const val MAX_AREA_RATIO = 0.93

    /**
     * How much of its own quad the contour has to actually fill. A sheet of paper
     * fills its outline almost completely; an L-shaped shadow or a run of desk
     * clutter that happens to span four corners does not.
     *
     * Raised from 0.72: the region masks' closing step (below) used to bridge
     * across a real gap between the page and a separate bright object sitting
     * near it — a stamp, a notepad, a business card on a dark desk — producing
     * one blob that still passed a loose fill check because the objects were
     * packed close together. 0.72 was loose enough to let that blob through as
     * "the page". This alone does not fully separate the two cases; see the
     * fill-squared scoring below for the other half of the fix.
     */
    private const val MIN_FILL_RATIO = 0.8

    /**
     * Tolerances tried when collapsing a contour to four corners, as a fraction
     * of its perimeter. One fixed value is too brittle — a clipboard clip, a
     * dog-eared corner or a slightly wavy edge adds vertices that a tight
     * tolerance will not merge, and the page is then thrown away for having five
     * corners instead of four.
     */
    private val EPSILONS = doubleArrayOf(0.015, 0.02, 0.03, 0.045, 0.06, 0.08)

    /**
     * Longest edge handed to OpenCV.
     *
     * A 12-megapixel photograph converts to a Mat of about 48 MB, and the
     * resize that follows needs another. That allocation can fail outright on a
     * mid-range phone — and because [detect] treats any failure as "no page
     * found", it failed *silently*: the preview would find the page, the
     * shutter would fire, and the saved page would come out uncropped. Shrinking
     * first costs nothing, since detection works on a smaller copy regardless.
     */
    private const val DETECT_INPUT_EDGE = 1200

    /** True when OpenCV's native library is present and initialised. */
    fun isAvailable(): Boolean = OpenCvLoader.ensureLoaded()

    /**
     * Returns the detected page corners in [bitmap]'s own pixel coordinates,
     * or null when nothing convincing was found.
     *
     * @param expectedRatio the subject's known long/short edge ratio (e.g.
     *   [DocumentFormat.ID_CARD]'s), when the caller knows in advance it is
     *   looking for something specific rather than an arbitrary page. This
     *   both lets a much smaller candidate qualify and rejects candidates
     *   whose shape does not match, instead of scoring every contour as if it
     *   were a full-page document.
     */
    fun detect(bitmap: Bitmap, expectedRatio: Float? = null): Quad? {
        if (!OpenCvLoader.ensureLoaded()) return null

        val working = shrinkForDetection(bitmap)
        val source = Mat()
        return try {
            Utils.bitmapToMat(working, source)
            val found = detectInternal(source, working.width, working.height, expectedRatio)
                ?: return null
            // Back into the caller's own pixel coordinates.
            val factor = bitmap.width.toFloat() / working.width
            found.scaled(factor).clampTo(bitmap.width, bitmap.height)
        } catch (t: Throwable) {
            null
        } finally {
            source.release()
            if (working !== bitmap) working.recycle()
        }
    }

    private fun shrinkForDetection(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= DETECT_INPUT_EDGE) return bitmap
        val factor = DETECT_INPUT_EDGE.toFloat() / longest
        return runCatching {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * factor).toInt().coerceAtLeast(1),
                (bitmap.height * factor).toInt().coerceAtLeast(1),
                true
            )
        }.getOrDefault(bitmap)
    }

    private data class Candidate(val quad: Quad, val score: Double)

    private fun detectInternal(
        source: Mat,
        srcWidth: Int,
        srcHeight: Int,
        expectedRatio: Float? = null
    ): Quad? {
        val longestEdge = maxOf(srcWidth, srcHeight).toDouble()
        val scale = if (longestEdge > WORK_EDGE) WORK_EDGE / longestEdge else 1.0
        val inverseScale = (1.0 / scale).toFloat()

        val work = Mat()
        val gray = Mat()
        val blurred = Mat()

        return try {
            if (scale < 1.0) {
                Imgproc.resize(source, work, Size(), scale, scale, Imgproc.INTER_AREA)
            } else {
                source.copyTo(work)
            }

            Imgproc.cvtColor(work, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

            val frameArea = (work.width() * work.height()).toDouble()
            val minAreaRatio = if (expectedRatio != null) MIN_AREA_RATIO_SMALL_SUBJECT else MIN_AREA_RATIO
            val candidates = ArrayList<Candidate>()
            edgeMask(blurred).useMat { candidates += candidatesIn(it, frameArea, minAreaRatio, expectedRatio) }
            regionMask(blurred, invert = false).useMat { candidates += candidatesIn(it, frameArea, minAreaRatio, expectedRatio) }
            regionMask(blurred, invert = true).useMat { candidates += candidatesIn(it, frameArea, minAreaRatio, expectedRatio) }

            candidates.maxByOrNull { it.score }
                ?.quad
                ?.scaled(inverseScale)
                ?.clampTo(srcWidth, srcHeight)
        } finally {
            listOf(work, gray, blurred).forEach { it.release() }
        }
    }

    // ------------------------------------------------------------------ masks

    /** Canny edges, with small gaps closed so a shadow-broken edge stays one contour. */
    private fun edgeMask(blurred: Mat): Mat {
        val edges = Mat()
        val closed = Mat()
        return try {
            val otsuOut = Mat()
            val otsu = try {
                Imgproc.threshold(
                    blurred, otsuOut, 0.0, 255.0,
                    Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU
                )
            } finally {
                otsuOut.release()
            }
            Imgproc.Canny(blurred, edges, otsu * 0.5, otsu)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()
            closed
        } finally {
            edges.release()
        }
    }

    /**
     * A solid mask of the light half of the image (or the dark half when
     * [invert] is set), cleaned up so the page becomes one filled blob.
     *
     * Closing first joins the page across printed text; opening afterwards drops
     * the specks — dust, cable, a pen — that would otherwise become contours.
     *
     * The closing kernel used to be 11×11 (on the ~700px working frame). That is
     * wide enough to bridge across a real, visible gap between the page and a
     * separate bright object sitting near it on a dark desk — a stamp, a
     * notepad, a business card — welding them into one blob that then got
     * reported as "the page", cropping in the neighbouring clutter along with
     * it. 5×5 still closes the small gaps a page's own printed text or a fold
     * leaves in its outline, without reaching far enough to weld it to
     * something it isn't.
     */
    private fun regionMask(blurred: Mat, invert: Boolean): Mat {
        val mask = Mat()
        val mode = if (invert) Imgproc.THRESH_BINARY_INV else Imgproc.THRESH_BINARY
        Imgproc.threshold(blurred, mask, 0.0, 255.0, mode or Imgproc.THRESH_OTSU)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
        kernel.release()
        return mask
    }

    // ------------------------------------------------------------- candidates

    private fun candidatesIn(
        mask: Mat,
        frameArea: Double,
        minAreaRatio: Double = MIN_AREA_RATIO,
        expectedRatio: Float? = null
    ): List<Candidate> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        return try {
            Imgproc.findContours(
                mask, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            contours
                .sortedByDescending { Imgproc.contourArea(it) }
                .take(8)
                .mapNotNull { it.toCandidate(frameArea, minAreaRatio, expectedRatio) }
        } catch (t: Throwable) {
            emptyList()
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
        }
    }

    private fun MatOfPoint.toCandidate(
        frameArea: Double,
        minAreaRatio: Double,
        expectedRatio: Float?
    ): Candidate? {
        val contourArea = Imgproc.contourArea(this)
        if (contourArea < frameArea * minAreaRatio) return null
        if (contourArea > frameArea * MAX_AREA_RATIO) return null

        val curve = MatOfPoint2f(*toArray())
        try {
            val quad = approxQuad(curve) ?: boxQuad(curve, contourArea) ?: return null
            if (!hasSaneAngles(quad)) return null

            val area = quadArea(quad)
            if (area < frameArea * minAreaRatio || area > frameArea * MAX_AREA_RATIO) return null

            val fill = (contourArea / area).coerceAtMost(1.0)
            if (fill < MIN_FILL_RATIO) return null

            // Fill is squared deliberately: a merged blob (page plus a nearby
            // object the closing step welded to it) is usually still bigger
            // than the true page even after the stricter MIN_FILL_RATIO above,
            // so ranking by area alone would still let it win. Squaring the
            // fill term makes a tight, clean quad worth more than a larger,
            // looser one even when both clear the minimum — a clean page
            // outranks the sprawling blob a busy background sometimes produces.
            var score = area * fill * fill

            // When the caller knows the subject's shape (an ID card, say), a
            // candidate that does not roughly match it is almost certainly the
            // desk or the table it is sitting on rather than the subject
            // itself — reject it outright instead of letting sheer size win.
            if (expectedRatio != null) {
                val w = quad.outputWidth.toDouble()
                val h = quad.outputHeight.toDouble()
                val measured = maxOf(w, h) / minOf(w, h)
                val diff = abs(measured - expectedRatio) / expectedRatio
                if (diff > EXPECTED_RATIO_TOLERANCE) return null
                val ratioFactor = 1.0 - (diff / EXPECTED_RATIO_TOLERANCE)
                score *= ratioFactor.coerceIn(0.1, 1.0)
            }

            return Candidate(quad, score)
        } catch (t: Throwable) {
            return null
        } finally {
            curve.release()
        }
    }

    /** Collapses a contour to four convex corners, widening the tolerance until it fits. */
    private fun approxQuad(curve: MatOfPoint2f): Quad? {
        val perimeter = Imgproc.arcLength(curve, true)
        if (perimeter <= 0.0) return null

        for (epsilon in EPSILONS) {
            val approx = MatOfPoint2f()
            try {
                Imgproc.approxPolyDP(curve, approx, epsilon * perimeter, true)
                if (approx.total() != 4L) continue

                val points = approx.toArray()
                val hull = MatOfPoint(*points.map { Point(it.x, it.y) }.toTypedArray())
                val convex = Imgproc.isContourConvex(hull)
                hull.release()
                if (!convex) continue

                return Quad.ordered(points.map { PointF(it.x.toFloat(), it.y.toFloat()) })
            } finally {
                approx.release()
            }
        }
        return null
    }

    /**
     * Last resort: the tightest rotated rectangle around the contour.
     *
     * This is what rescues a page whose outline is interrupted — by the clip of a
     * clipboard, by the edge running off the frame, by a hand holding it down.
     * Requiring the contour to fill the rectangle keeps it from boxing an
     * L-shaped or scattered blob into a plausible-looking page.
     */
    private fun boxQuad(curve: MatOfPoint2f, contourArea: Double): Quad? {
        val rect = Imgproc.minAreaRect(curve)
        val boxArea = rect.size.width * rect.size.height
        if (boxArea <= 0.0) return null
        if (contourArea / boxArea < MIN_FILL_RATIO) return null

        val corners = Array(4) { Point() }
        rect.points(corners)
        return Quad.ordered(corners.map { PointF(it.x.toFloat(), it.y.toFloat()) })
    }

    /**
     * A sheet of paper photographed at an angle is still roughly rectangular.
     * The window is wide enough for a page shot from well off to one side, and
     * still narrow enough to throw out the random four-sided blobs that a busy
     * background produces.
     */
    private fun hasSaneAngles(quad: Quad): Boolean {
        val pts = quad.points
        return pts.indices.all { i ->
            val prev = pts[(i + 3) % 4]
            val current = pts[i]
            val next = pts[(i + 1) % 4]
            angleBetween(prev, current, next) in 50.0..130.0
        }
    }

    private fun angleBetween(a: PointF, vertex: PointF, b: PointF): Double {
        val v1x = (a.x - vertex.x).toDouble()
        val v1y = (a.y - vertex.y).toDouble()
        val v2x = (b.x - vertex.x).toDouble()
        val v2y = (b.y - vertex.y).toDouble()
        val dot = v1x * v2x + v1y * v2y
        val mag = Math.sqrt(v1x * v1x + v1y * v1y) * Math.sqrt(v2x * v2x + v2y * v2y)
        if (mag == 0.0) return 0.0
        return Math.toDegrees(Math.acos((dot / mag).coerceIn(-1.0, 1.0)))
    }

    /** Shoelace formula. */
    private fun quadArea(quad: Quad): Double {
        val pts = quad.points
        var sum = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            sum += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
        }
        return abs(sum) / 2.0
    }

    /**
     * Cheap blur estimate: the variance of the Laplacian. Used by auto-capture to
     * refuse to shoot while the camera is still focusing. Higher is sharper.
     */
    fun sharpness(bitmap: Bitmap): Double {
        if (!OpenCvLoader.ensureLoaded()) return Double.MAX_VALUE
        val src = Mat()
        val gray = Mat()
        val laplacian = Mat()
        return try {
            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            val mean = org.opencv.core.MatOfDouble()
            val stdDev = org.opencv.core.MatOfDouble()
            Core.meanStdDev(laplacian, mean, stdDev)
            val sd = stdDev.toArray().firstOrNull() ?: 0.0
            mean.release()
            stdDev.release()
            sd * sd
        } catch (t: Throwable) {
            Double.MAX_VALUE
        } finally {
            listOf(src, gray, laplacian).forEach { it.release() }
        }
    }

    /** OpenCV Mats are not Closeable, so `use` does not apply to them. */
    private inline fun <R> Mat.useMat(block: (Mat) -> R): R = try {
        block(this)
    } finally {
        release()
    }
}
