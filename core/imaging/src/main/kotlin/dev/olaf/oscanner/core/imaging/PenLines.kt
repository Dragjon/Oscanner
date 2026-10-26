package dev.olaf.oscanner.core.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Underlines and rings drawn through print. A pen line touching the letters joins a line of them
 * into one mark, which then reads as handwriting, and goes, as a whole. Printed letters are marks
 * of their own about a letter high; in marks far bigger, strokes running straight on for longer
 * than any letter's are a line's.
 */
object PenLines {
    /** The line strokes in [ink] (white on black), as a mask; empty when nothing is joined up. */
    fun find(ink: Mat): Mat {
        val lines = Mat.zeros(ink.size(), CvType.CV_8UC1)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
        centroids.release()
        val st = IntArray(count * 5).also { stats.get(0, 0, it) }
        stats.release()
        val marks = (1 until count).filter { st[it * 5 + 4] >= MIN_AREA && st[it * 5 + 3] >= MIN_HEIGHT }
        val unit = marks.map { st[it * 5 + 3] }.sorted().getOrNull(marks.size / 2)
        val big = BooleanArray(count)
        // A heading's big letters are big too, but about as wide as tall; letters strung together
        // by a line run on along it.
        if (unit != null) {
            marks.filter { (st[it * 5 + 2] > JOINED_WIDE * unit || st[it * 5 + 3] > JOINED_TALL * unit) && max(st[it * 5 + 2], st[it * 5 + 3]) > JOINED_LONG * min(st[it * 5 + 2], st[it * 5 + 3]) }
                .forEach { big[it] = true }
        }
        if (unit == null || big.none { it }) {
            labels.release()
            return lines
        }
        val ids = IntArray(ink.rows() * ink.cols()).also { labels.get(0, 0, it) }
        labels.release()
        val joined = Mat(ink.rows(), ink.cols(), CvType.CV_8UC1)
        joined.put(0, 0, ByteArray(ids.size) { if (big[ids[it]]) -1 else 0 })
        // A pen line a couple of pixels wide wobbles: thickened a little, it runs straight.
        val thick = Mat()
        Imgproc.dilate(joined, thick, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val length = max(5, (RUN * unit).toInt() or 1)
        val part = Mat()
        for (k in 0 until ANGLES) {
            val kernel = lineKernel(length, 180.0 * k / ANGLES)
            Imgproc.morphologyEx(thick, part, Imgproc.MORPH_OPEN, kernel)
            Core.bitwise_or(lines, part, lines)
            kernel.release()
        }
        Core.bitwise_and(lines, joined, lines)
        // Thin strokes only: a heading's thick letters run straight on too.
        val dist = Mat()
        Imgproc.distanceTransform(ink, dist, Imgproc.DIST_L2, 3)
        val depths = FloatArray(ids.size).also { dist.get(0, 0, it) }
        val half = depths.filter { it > 0f }.sorted().let { it.getOrElse(it.size / 2) { 0f } }
        Imgproc.dilate(dist, dist, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        val thickInk = Mat()
        Imgproc.threshold(dist, thickInk, THIN * half, 255.0, Imgproc.THRESH_BINARY)
        thickInk.convertTo(thickInk, CvType.CV_8UC1)
        Core.subtract(lines, thickInk, lines)
        Imgproc.dilate(lines, lines, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        listOf(joined, thick, part, dist, thickInk).forEach(Mat::release)
        return lines
    }

    /** A straight line [length] pixels long through the middle of a square, at [degrees]. */
    private fun lineKernel(length: Int, degrees: Double): Mat {
        val kernel = Mat.zeros(length, length, CvType.CV_8UC1)
        val c = (length - 1) / 2.0
        val dx = cos(Math.toRadians(degrees)) * c
        val dy = sin(Math.toRadians(degrees)) * c
        Imgproc.line(kernel, Point(Math.round(c - dx).toDouble(), Math.round(c - dy).toDouble()), Point(Math.round(c + dx).toDouble(), Math.round(c + dy).toDouble()), Scalar(1.0), 1)
        return kernel
    }

    /** Marks smaller than this, or less tall than [MIN_HEIGHT], are specks, not letters. */
    private const val MIN_AREA = 6
    private const val MIN_HEIGHT = 4
    /** Marks wider than this many letter heights, or taller than [JOINED_TALL], are letters joined by something. */
    private const val JOINED_WIDE = 3.0
    private const val JOINED_TALL = 2.0
    /** ...and at least this many times longer one way than the other. */
    private const val JOINED_LONG = 1.3
    /** A stroke running straight this many letter heights on is a line's, not a letter's. */
    private const val RUN = 1.5
    private const val ANGLES = 18
    /** A line's stroke is at most this many times as deep as the ink's middling depth. */
    private const val THIN = 3.0
}
