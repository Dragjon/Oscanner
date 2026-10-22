package dev.olaf.oscanner.core.imaging

import android.graphics.Bitmap
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Splits a photographed two-page spread into its left and right pages (PLAN.md §5.6).
 *
 * The spread is straightened, then the spine is found as the darkest vertical valley in the
 * middle band of the column brightness profile (the gutter's shadow). That column is mapped back
 * through the inverse perspective transform, so the cut follows the spine in the photo even when
 * the book was shot at an angle. No dewarping: curved pages are flattened only after a
 * freedom-to-operate review (⚠ in PLAN.md §2).
 */
object BookSplitter {

    private const val WORK_SIDE = 900.0
    /** The spine is searched for between these fractions of the spread's width. */
    private const val BAND_START = 0.35
    private const val BAND_END = 0.65
    /** A valley must be at least this much darker than the band's median, or we cut in the middle. */
    private const val MIN_DEPTH = 6.0

    fun split(source: Bitmap, spread: Quad): Pair<Quad, Quad> {
        PageRenderer.ensureLoaded()
        val w = source.width.toDouble()
        val h = source.height.toDouble()
        val (mw, mh) = QuadGeometry.warpSize(spread, source.width, source.height)
        val scale = WORK_SIDE / maxOf(mw, mh)
        val outW = (mw * scale).coerceAtLeast(16.0)
        val outH = (mh * scale).coerceAtLeast(16.0)

        val src = MatOfPoint2f(*spread.corners.map { Point(it.x * w, it.y * h) }.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(outW, 0.0), Point(outW, outH), Point(0.0, outH))
        val forward = Imgproc.getPerspectiveTransform(src, dst)
        val rgba = Mat()
        Utils.bitmapToMat(source, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val flat = Mat()
        Imgproc.warpPerspective(gray, flat, forward, Size(outW, outH))
        gray.release()

        // A spread is wider than tall along its spine's axis: two pages side by side. Taller
        // than wide, the book was photographed turned, its pages one above the other, and the
        // spine runs across.
        val across = outH > outW
        val spine = if (across) {
            val t = Mat()
            Core.transpose(flat, t)
            spineFraction(t).also { t.release() }
        } else {
            spineFraction(flat)
        }
        flat.release()

        // Where the spine meets the spread's edges, back in the photo.
        val inverse = forward.inv()
        val warpedEnds = if (across) {
            MatOfPoint2f(Point(0.0, spine * outH), Point(outW, spine * outH))
        } else {
            MatOfPoint2f(Point(spine * outW, 0.0), Point(spine * outW, outH))
        }
        val photoEnds = MatOfPoint2f()
        Core.perspectiveTransform(warpedEnds, photoEnds, inverse)
        val (a, b) = photoEnds.toArray().map { Corner((it.x / w).toFloat(), (it.y / h).toFloat()) }
        listOf(src, dst, forward, inverse, warpedEnds, photoEnds).forEach(Mat::release)

        return if (across) {
            // The upper page first.
            Quad(spread.topLeft, spread.topRight, b, a) to Quad(a, b, spread.bottomRight, spread.bottomLeft)
        } else {
            Quad(spread.topLeft, a, b, spread.bottomLeft) to Quad(a, spread.topRight, spread.bottomRight, b)
        }
    }

    /** Spine position as a fraction of the straightened spread's width. */
    internal fun spineFraction(flat: Mat): Double {
        val columns = Mat()
        // Mean of each column.
        Core.reduce(flat, columns, 0, Core.REDUCE_AVG, org.opencv.core.CvType.CV_64F)
        val profile = DoubleArray(columns.cols()) { columns.get(0, it)[0] }
        columns.release()
        return valley(profile)
    }

    /** Pure-Kotlin part, so it can be unit tested: darkest smoothed column in the middle band. */
    internal fun valley(profile: DoubleArray): Double {
        val n = profile.size
        if (n < 8) return 0.5
        val radius = (n / 60).coerceAtLeast(2)
        val smooth = DoubleArray(n) { i ->
            val from = (i - radius).coerceAtLeast(0)
            val to = (i + radius).coerceAtMost(n - 1)
            (from..to).sumOf { profile[it] } / (to - from + 1)
        }
        val start = (n * BAND_START).toInt()
        val end = (n * BAND_END).toInt().coerceAtMost(n - 1)
        val band = (start..end)
        val darkest = band.minBy { smooth[it] }
        val median = band.map { smooth[it] }.sorted()[band.count() / 2]
        return if (median - smooth[darkest] >= MIN_DEPTH) darkest.toDouble() / (n - 1) else 0.5
    }
}
