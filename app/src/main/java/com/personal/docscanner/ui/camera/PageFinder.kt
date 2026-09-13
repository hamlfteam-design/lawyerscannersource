package com.personal.docscanner.ui.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.personal.docscanner.scan.EdgeDetector
import com.personal.docscanner.scan.Quad

/**
 * Watches the camera preview and finds the page in it, purely so the
 * viewfinder can draw an outline around it while you aim.
 *
 * This used to also decide, on its own, when to fire the shutter after the
 * page had been held still for about a second. That auto-fire was removed:
 * every page is now shot by a deliberate press of the shutter button, and
 * detection here only feeds the on-screen outline that helps you frame the
 * shot. The photo itself is still auto-cropped and auto-enhanced the moment
 * it is taken — that part happens in [com.personal.docscanner.ui.scan.ScanViewModel.capturePage].
 */
class PageFinder(
    private val onFrame: (Frame) -> Unit
) : ImageAnalysis.Analyzer {

    /** What the preview overlay draws. Corners are normalised to 0..1. */
    data class Frame(
        val quad: Quad?,
        val frameWidth: Int,
        val frameHeight: Int
    )

    private var lastRunNanos = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.nanoTime()
            // Detection costs more than a frame interval, and running it on
            // every frame would only heat the phone up for the same answer.
            if (now - lastRunNanos < MIN_INTERVAL_NANOS) return
            lastRunNanos = now

            val bitmap = image.toUprightBitmap() ?: return
            val width = bitmap.width
            val height = bitmap.height
            val quad = EdgeDetector.detect(bitmap)?.normalised(width, height)
            bitmap.recycle()

            onFrame(Frame(quad = quad, frameWidth = width, frameHeight = height))
        } catch (t: Throwable) {
            // A dropped frame is not worth taking the camera down for.
        } finally {
            image.close()
        }
    }

    private fun Quad.normalised(width: Int, height: Int): Quad {
        val w = width.toFloat()
        val h = height.toFloat()
        return Quad(
            PointF(topLeft.x / w, topLeft.y / h),
            PointF(topRight.x / w, topRight.y / h),
            PointF(bottomRight.x / w, bottomRight.y / h),
            PointF(bottomLeft.x / w, bottomLeft.y / h)
        )
    }

    /**
     * Analysis frames arrive in sensor orientation with the rotation left in
     * metadata. Applying it here means the quad we hand out is already in the
     * same orientation the user is looking at.
     */
    private fun ImageProxy.toUprightBitmap(): Bitmap? {
        val source = runCatching { toBitmap() }.getOrNull() ?: return null
        val degrees = imageInfo.rotationDegrees
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrNull() ?: return source
        if (rotated !== source) source.recycle()
        return rotated
    }

    private companion object {
        /** Roughly six detections a second. */
        const val MIN_INTERVAL_NANOS = 160_000_000L
    }
}
