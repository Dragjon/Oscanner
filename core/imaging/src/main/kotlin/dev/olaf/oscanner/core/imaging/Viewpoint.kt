package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Quad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Where the camera was over a straightened page: the point under it, [x] in page widths and
 * [y] in page heights from the top left, and its [distance] above the paper in page widths.
 * Paper raised toward the camera looks bigger, by distance / (distance − height), about the
 * point under it; that's how a page's bend shows how high each part of it stands.
 */
data class Viewpoint(val x: Double, val y: Double, val distance: Double) {

    companion object {
        /** A 35 mm equivalent focal length most phones' main cameras are near. */
        private const val PHONE_FOCAL_35 = 26.0
        private const val FULL_FRAME_DIAGONAL = 43.27

        /**
         * A camera straight over the middle of a page [aspect] (height / width) times as tall as
         * it's wide, close enough for the page to fill a phone's picture.
         */
        fun above(aspect: Double) = Viewpoint(0.5, 0.5, 0.7 * hypot(1.0, aspect))

        /**
         * From where [quad] (normalized) sits in a [photoWidth]×[photoHeight] photo taken at a
         * 35 mm equivalent focal length of [focal35], for a page straightened to [pageWidth]×[pageHeight].
         * Null when the corners don't fit any camera in front of the page.
         */
        fun fromQuad(quad: Quad, photoWidth: Int, photoHeight: Int, pageWidth: Double, pageHeight: Double, focal35: Double? = null): Viewpoint? {
            val f = (focal35?.takeIf { it in 8.0..300.0 } ?: PHONE_FOCAL_35) / FULL_FRAME_DIAGONAL * hypot(photoWidth.toDouble(), photoHeight.toDouble())
            val cx = photoWidth / 2.0
            val cy = photoHeight / 2.0
            val sq = Homography.squareTo(quad).m
            // Page pixels to photo pixels, then the camera's own view of it: K⁻¹ · H.
            val h = DoubleArray(9) { i ->
                val r = i / 3
                val c = i % 3
                val scale = if (r == 0) photoWidth.toDouble() else if (r == 1) photoHeight.toDouble() else 1.0
                sq[i] * scale / (if (c == 0) pageWidth else if (c == 1) pageHeight else 1.0)
            }
            val m = DoubleArray(9) { i ->
                val r = i / 3
                val c = i % 3
                when (r) {
                    0 -> (h[c] - cx * h[6 + c]) / f
                    1 -> (h[3 + c] - cy * h[6 + c]) / f
                    else -> h[6 + c]
                }
            }
            fun column(c: Int) = doubleArrayOf(m[c], m[3 + c], m[6 + c])
            val m1 = column(0)
            val m2 = column(1)
            val m3 = column(2)
            fun norm(v: DoubleArray) = sqrt(v.sumOf { it * it })
            var scale = 2 / (norm(m1) + norm(m2))
            // The page lies in front of the camera.
            if (m3[2] * scale < 0) scale = -scale
            val r1 = m1.map { it * scale }
            val r2 = m2.map { it * scale }
            val t = m3.map { it * scale }
            val r3 = listOf(r1[1] * r2[2] - r1[2] * r2[1], r1[2] * r2[0] - r1[0] * r2[2], r1[0] * r2[1] - r1[1] * r2[0])
            fun dot(a: List<Double>, b: List<Double>) = a.indices.sumOf { a[it] * b[it] }
            // The camera's centre in page coordinates: −Rᵀ·t.
            val distance = abs(dot(r3, t)) / pageWidth
            val view = Viewpoint(-dot(r1, t) / pageWidth, -dot(r2, t) / pageHeight, distance)
            return view.takeIf { distance in 0.2..20.0 && view.x.isFinite() && view.y.isFinite() }
        }
    }
}
