package dev.olaf.oscanner.core.imaging

import android.graphics.Bitmap
import dev.olaf.oscanner.core.model.Quad
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A page's fingerprint, for telling when the same page is scanned twice: the page straightened
 * onto a small grid, as how much ink each cell holds against the paper around it, so the light
 * and exposure drop out. Two shots of one page match closely; two different pages, even of the
 * same book, don't, since their lines of text fall in different places.
 */
object PageSignature {

    /** [photo]'s page inside [quad] (normalized corners), as a signature. */
    fun of(photo: Bitmap, quad: Quad): FloatArray {
        PageRenderer.ensureLoaded()
        val rgba = Mat()
        Utils.bitmapToMat(photo, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return of(gray, quad).also { gray.release() }
    }

    /** As [of], from an 8-bit gray image. */
    fun of(gray: Mat, quad: Quad): FloatArray {
        val w = gray.cols().toDouble()
        val h = gray.rows().toDouble()
        val src = MatOfPoint2f(*quad.corners.map { Point(it.x * w, it.y * h) }.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(STRAIGHT * W - 1.0, 0.0), Point(STRAIGHT * W - 1.0, STRAIGHT * H - 1.0), Point(0.0, STRAIGHT * H - 1.0))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        // Straightened at a few times the grid, then shrunk: text averages into gray bands.
        val big = Mat()
        Imgproc.warpPerspective(gray, big, m, Size(STRAIGHT * W.toDouble(), STRAIGHT * H.toDouble()), Imgproc.INTER_AREA)
        val small = Mat()
        Imgproc.resize(big, small, Size(W.toDouble(), H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        listOf(src, dst, m, big).forEach(Mat::release)
        val s = Mat()
        small.convertTo(s, CvType.CV_32F)
        small.release()
        // The paper around each cell, and the ink against it.
        val paper = Mat()
        Imgproc.dilate(s, paper, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)))
        Imgproc.GaussianBlur(paper, paper, Size(0.0, 0.0), 4.0)
        Core.max(paper, org.opencv.core.Scalar(1.0), paper)
        Core.divide(s, paper, s)
        Core.subtract(Mat.ones(s.size(), CvType.CV_32F), s, s)
        Imgproc.GaussianBlur(s, s, Size(0.0, 0.0), 0.8)
        val out = FloatArray(W * H).also { s.get(0, 0, it) }
        listOf(s, paper).forEach(Mat::release)
        return out
    }

    /**
     * How alike two signatures are, −1 to 1: the best correlation of their middles over shifts of
     * a few cells, so two crops a little apart still line up.
     */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        var best = -1f
        val iw = W - 2 * SHIFT
        val ih = H - 2 * SHIFT
        val n = iw * ih
        fun at(sig: FloatArray, x: Int, y: Int) = sig[y * W + x]
        // a's middle, normalized once.
        val ma = FloatArray(n)
        var mean = 0f
        for (y in 0 until ih) for (x in 0 until iw) mean += at(a, x + SHIFT, y + SHIFT).also { ma[y * iw + x] = it }
        mean /= n
        var sd = 0f
        for (i in 0 until n) { ma[i] -= mean; sd += ma[i] * ma[i] }
        sd = sqrt(sd / n)
        if (sd < 1e-6f) return 0f
        for (dy in -SHIFT..SHIFT) for (dx in -SHIFT..SHIFT) {
            var mb = 0f
            for (y in 0 until ih) for (x in 0 until iw) mb += at(b, x + SHIFT + dx, y + SHIFT + dy)
            mb /= n
            var cross = 0f
            var vb = 0f
            for (y in 0 until ih) for (x in 0 until iw) {
                val v = at(b, x + SHIFT + dx, y + SHIFT + dy) - mb
                cross += ma[y * iw + x] * v
                vb += v * v
            }
            val sb = sqrt(vb / n)
            if (sb < 1e-6f) continue
            best = max(best, cross / n / (sd * sb))
        }
        return best
    }

    /** Two pages this alike are the same page. Same page shot twice: 0.8 or more; different pages: under 0.5. */
    const val SAME = 0.7f

    private const val W = 72
    private const val H = 96
    private const val SHIFT = 3
    private const val STRAIGHT = 4
}
