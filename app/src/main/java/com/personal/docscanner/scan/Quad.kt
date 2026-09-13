package com.personal.docscanner.scan

import android.graphics.PointF
import kotlin.math.hypot

/**
 * Four corners of a detected page, always stored in the order
 * top-left, top-right, bottom-right, bottom-left.
 */
data class Quad(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF
) {
    val points: List<PointF>
        get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Width of the warped output: the longer of the two horizontal edges. */
    val outputWidth: Int
        get() = maxOf(dist(topLeft, topRight), dist(bottomLeft, bottomRight))
            .toInt().coerceAtLeast(1)

    /** Height of the warped output: the longer of the two vertical edges. */
    val outputHeight: Int
        get() = maxOf(dist(topLeft, bottomLeft), dist(topRight, bottomRight))
            .toInt().coerceAtLeast(1)

    /**
     * Turns corners expressed in 0..1 of a frame into pixel coordinates of an
     * image of the given size. Used to carry a quad found on the small preview
     * frame over onto the full-resolution photograph.
     */
    fun denormalized(width: Int, height: Int): Quad {
        val w = width.toFloat()
        val h = height.toFloat()
        return Quad(
            PointF(topLeft.x * w, topLeft.y * h),
            PointF(topRight.x * w, topRight.y * h),
            PointF(bottomRight.x * w, bottomRight.y * h),
            PointF(bottomLeft.x * w, bottomLeft.y * h)
        )
    }

    fun scaled(factor: Float): Quad = Quad(
        PointF(topLeft.x * factor, topLeft.y * factor),
        PointF(topRight.x * factor, topRight.y * factor),
        PointF(bottomRight.x * factor, bottomRight.y * factor),
        PointF(bottomLeft.x * factor, bottomLeft.y * factor)
    )

    fun withCorner(index: Int, point: PointF): Quad = when (index) {
        0 -> copy(topLeft = point)
        1 -> copy(topRight = point)
        2 -> copy(bottomRight = point)
        else -> copy(bottomLeft = point)
    }

    /** Clamps every corner inside a `width` x `height` image. */
    fun clampTo(width: Int, height: Int): Quad = Quad(
        clamp(topLeft, width, height),
        clamp(topRight, width, height),
        clamp(bottomRight, width, height),
        clamp(bottomLeft, width, height)
    )

    fun serialize(): String =
        points.joinToString(";") { "${it.x},${it.y}" }

    companion object {
        fun full(width: Int, height: Int): Quad = Quad(
            PointF(0f, 0f),
            PointF(width.toFloat(), 0f),
            PointF(width.toFloat(), height.toFloat()),
            PointF(0f, height.toFloat())
        )

        /** A centred quad with a small inset, used when detection finds nothing. */
        fun inset(width: Int, height: Int, fraction: Float = 0.08f): Quad {
            val dx = width * fraction
            val dy = height * fraction
            return Quad(
                PointF(dx, dy),
                PointF(width - dx, dy),
                PointF(width - dx, height - dy),
                PointF(dx, height - dy)
            )
        }

        fun parse(raw: String?): Quad? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(';')
            if (parts.size != 4) return null
            val pts = parts.mapNotNull { part ->
                val xy = part.split(',')
                val x = xy.getOrNull(0)?.toFloatOrNull()
                val y = xy.getOrNull(1)?.toFloatOrNull()
                if (x == null || y == null) null else PointF(x, y)
            }
            if (pts.size != 4) return null
            return Quad(pts[0], pts[1], pts[2], pts[3])
        }

        /**
         * Puts four unordered corners into TL, TR, BR, BL order. Sorting by
         * (x + y) finds the top-left and bottom-right; (x - y) separates the
         * other two. This is orientation-agnostic, so a page held sideways
         * still comes out with consistent corners.
         */
        fun ordered(corners: List<PointF>): Quad {
            require(corners.size == 4) { "expected 4 corners, got ${corners.size}" }
            val bySum = corners.sortedBy { it.x + it.y }
            val byDiff = corners.sortedBy { it.x - it.y }
            val tl = bySum.first()
            val br = bySum.last()
            val bl = byDiff.first()
            val tr = byDiff.last()
            // Degenerate input (e.g. three collinear points) can alias corners;
            // fall back to the raw order rather than emitting a broken quad.
            val distinct = listOf(tl, tr, br, bl).distinct()
            return if (distinct.size == 4) {
                Quad(tl, tr, br, bl)
            } else {
                Quad(corners[0], corners[1], corners[2], corners[3])
            }
        }

        private fun dist(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)

        private fun clamp(p: PointF, width: Int, height: Int) = PointF(
            p.x.coerceIn(0f, width.toFloat()),
            p.y.coerceIn(0f, height.toFloat())
        )
    }
}
