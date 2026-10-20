package dev.olaf.oscanner.feature.capture

import dev.olaf.oscanner.core.model.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Spots a reflection on the page in a preview frame: a patch of blown-out pixels while the rest
 * of the paper is not. A page that's simply bright white all over isn't glare.
 */
internal object Glare {

    /** Brighter than this (of 255) has lost all detail. */
    private const val BLOWN = 250.0
    /** Between these shares of the page, blown-out pixels are a reflection. */
    private const val MIN_SHARE = 0.005
    private const val MAX_SHARE = 0.25
    /** The paper itself must sit below this, or the whole page is just bright. */
    private const val PAPER_MAX = 235

    /** [frame] is the luminance plane; [quad] is normalized to it. */
    fun onPage(frame: Mat, quad: Quad): Boolean {
        val w = frame.cols().toDouble()
        val h = frame.rows().toDouble()
        val page = Mat.zeros(frame.size(), CvType.CV_8U)
        val outline = MatOfPoint(*quad.corners.map { Point(it.x * w, it.y * h) }.toTypedArray())
        Imgproc.fillPoly(page, listOf(outline), Scalar.all(255.0))
        outline.release()
        val area = Core.countNonZero(page)
        if (area == 0) {
            page.release()
            return false
        }
        val blown = Mat()
        Imgproc.threshold(frame, blown, BLOWN, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_and(blown, page, blown)
        val share = Core.countNonZero(blown).toDouble() / area
        blown.release()
        val paper = if (share in MIN_SHARE..MAX_SHARE) median(frame, page) else 255
        page.release()
        return share in MIN_SHARE..MAX_SHARE && paper < PAPER_MAX
    }

    private fun median(frame: Mat, mask: Mat): Int {
        val hist = Mat()
        Imgproc.calcHist(listOf(frame), MatOfInt(0), mask, hist, MatOfInt(256), MatOfFloat(0f, 256f))
        val counts = FloatArray(256)
        hist.get(0, 0, counts)
        hist.release()
        val half = counts.sum() / 2
        var seen = 0f
        counts.forEachIndexed { value, n ->
            seen += n
            if (seen >= half) return value
        }
        return 255
    }
}
