package dev.olaf.oscanner.core.imaging

import android.graphics.Bitmap
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/** A page outline found in one frame, normalized to that frame, plus how sharp the frame was. */
data class Detection(val quad: Quad?, val sharpness: Double)

/**
 * Finds the page in a grayscale frame. Our pipeline (PLAN.md §5.4): denoise, two candidate
 * edge maps (auto-threshold Canny, Otsu), outer contours, convex hull, polygon fit to four
 * corners, then score candidates on area and corner angles. Not thread-safe: one per thread.
 */
class DocumentDetector(
    /** How far from square a corner may look, in degrees. Whiteboards are shot at steeper angles. */
    private val angleTolerance: Double = 35.0,
) {

    private val blur = Mat()
    private val edges = Mat()
    private val binary = Mat()
    private val laplacian = Mat()
    private val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))

    /** [gray] is a single-channel 8-bit frame, ideally around 640 px on its long side. */
    fun detect(gray: Mat): Detection {
        Imgproc.medianBlur(gray, blur, 5)
        val sharpness = sharpness(gray)

        val median = median(blur)
        Imgproc.Canny(blur, edges, max(0.0, 0.66 * median), (1.33 * median).coerceIn(30.0, 255.0))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.threshold(blur, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

        val width = gray.cols()
        val height = gray.rows()
        val best = (candidates(edges) + candidates(binary))
            .mapNotNull { score(it, width, height) }
            .maxByOrNull { it.second }
            ?.first
        return Detection(best, sharpness)
    }

    /** Finds the page in a still photo; returns null when nothing page-shaped is there. */
    fun detect(bitmap: Bitmap): Quad? {
        val scale = DETECT_SIDE.toDouble() / max(bitmap.width, bitmap.height)
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        if (scale < 1.0) Imgproc.resize(gray, gray, Size(), scale, scale, Imgproc.INTER_AREA)
        return detect(gray).quad.also { gray.release() }
    }

    fun release() {
        listOf(blur, edges, binary, laplacian, kernel).forEach(Mat::release)
    }

    /** Variance of the Laplacian: low when the frame is blurred by motion or focus. */
    private fun sharpness(gray: Mat): Double {
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(laplacian, mean, std)
        val sd = std.toArray().firstOrNull() ?: 0.0
        mean.release()
        std.release()
        return sd * sd
    }

    private fun median(gray: Mat): Double {
        val bytes = ByteArray((gray.total() * gray.channels()).toInt())
        gray.get(0, 0, bytes)
        val histogram = IntArray(256)
        // Every 4th pixel is plenty for a threshold estimate.
        for (i in bytes.indices step 4) histogram[bytes[i].toInt() and 0xFF]++
        val half = histogram.sum() / 2
        var seen = 0
        for (v in 0..255) {
            seen += histogram[v]
            if (seen >= half) return v.toDouble()
        }
        return 128.0
    }

    /** Four-corner polygons fitted to the hulls of the largest outer contours of [mask]. */
    private fun candidates(mask: Mat): List<List<Point>> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val minArea = mask.total() * MIN_AREA_RATIO
        val result = contours
            .asSequence()
            .filter { Imgproc.contourArea(it) >= minArea * 0.5 }
            .sortedByDescending { Imgproc.contourArea(it) }
            .take(5)
            .mapNotNull { fitFour(hull(it)) }
            .toList()
        contours.forEach(Mat::release)
        return result
    }

    private fun hull(contour: MatOfPoint): MatOfPoint2f {
        val indices = MatOfInt()
        Imgproc.convexHull(contour, indices)
        val points = contour.toArray()
        val hull = MatOfPoint2f(*indices.toArray().map { points[it] }.toTypedArray())
        indices.release()
        return hull
    }

    /** Douglas–Peucker with a growing tolerance until four vertices remain. */
    private fun fitFour(hull: MatOfPoint2f): List<Point>? {
        val perimeter = Imgproc.arcLength(hull, true)
        val approx = MatOfPoint2f()
        try {
            for (step in 1..8) {
                Imgproc.approxPolyDP(hull, approx, perimeter * 0.01 * step, true)
                val n = approx.total().toInt()
                if (n == 4) return approx.toArray().toList()
                if (n < 4) return null
            }
            return null
        } finally {
            approx.release()
            hull.release()
        }
    }

    /** Normalized quad and a score, or null when the shape can't be a photographed page. */
    private fun score(points: List<Point>, width: Int, height: Int): Pair<Quad, Double>? {
        val quad = QuadGeometry.order(points.map { Corner((it.x / width).toFloat(), (it.y / height).toFloat()) })
        if (!QuadGeometry.isConvex(quad)) return null
        val areaRatio = QuadGeometry.area(quad.corners)
        if (areaRatio < MIN_AREA_RATIO || areaRatio > 0.995) return null
        // Two corners on the frame edge means the shape runs out of view: a wall, a table, or
        // a page that isn't fully in the picture. None of those can be scanned whole.
        val onBorder = quad.corners.count { it.x < BORDER || it.x > 1 - BORDER || it.y < BORDER || it.y > 1 - BORDER }
        if (onBorder >= 2) return null
        val angles = QuadGeometry.angles(quad)
        if (angles.any { abs(it - 90.0) > angleTolerance }) return null
        val skew = angles.sumOf { abs(it - 90.0) } / 4 / angleTolerance
        return inset(quad) to areaRatio * (1.0 - 0.5 * skew)
    }

    /**
     * Pulls each corner a hair toward the center. Contours sit on the boundary pixels, so the
     * raw outline keeps a sliver of desk along the edges once warped.
     */
    private fun inset(quad: Quad): Quad {
        val cx = quad.corners.map { it.x }.average().toFloat()
        val cy = quad.corners.map { it.y }.average().toFloat()
        return Quad.of(quad.corners.map { Corner(it.x + (cx - it.x) * INSET, it.y + (cy - it.y) * INSET) })
    }

    companion object {
        /** Pages smaller than this share of the frame are ignored. */
        const val MIN_AREA_RATIO = 0.12
        private const val DETECT_SIDE = 640
        /** Share of the frame counted as "on the edge". */
        private const val BORDER = 0.015f
        /** How far corners move toward the center, as a share of their distance to it. */
        private const val INSET = 0.012f
    }
}
