package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.InkMark
import dev.olaf.oscanner.core.model.PageEdits
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Removing things from a straightened page (PLAN.md §5.5, the classical tier). Everything to
 * remove becomes one mask; masked pixels are replaced by the page's own blank-paper estimate,
 * which on paper is exact and needs no model. Works on 8-bit RGB, before the look is applied.
 */
internal object Cleanup {

    /** Fills what [edits] removes, in place. */
    fun apply(rgb: Mat, edits: PageEdits) {
        val mask = mask(rgb, edits) ?: return
        val paper = Filters.backgroundOf(rgb)
        paper.copyTo(rgb, mask)
        paper.release()
        mask.release()
    }

    /** 255 where something will be removed, or null when nothing will. */
    fun mask(rgb: Mat, edits: PageEdits): Mat? {
        if (!edits.cleaned) return null
        val w = rgb.cols()
        val h = rgb.rows()
        val mask = Mat.zeros(h, w, CvType.CV_8UC1)
        if (edits.removePenInk) penInk(rgb).also { Core.bitwise_or(mask, it, mask); it.release() }
        if (edits.removeFingers) fingers(rgb).also { Core.bitwise_or(mask, it, mask); it.release() }
        // "Keep" protects from the automatic removals; painting "erase" always wins.
        draw(mask, edits.keep, 0.0)
        draw(mask, edits.erase, 255.0)
        if (Core.countNonZero(mask) == 0) {
            mask.release()
            return null
        }
        return mask
    }

    /**
     * Colored pen: blue, red or green strokes with real saturation. Black and gray print has
     * almost none, so it's left alone. Grown a little to catch the strokes' soft edges.
     */
    private fun penInk(rgb: Mat): Mat {
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val mask = Mat()
        val part = Mat()
        // OpenCV hue runs 0–180.
        val ranges = listOf(
            Scalar(90.0, 70.0, 30.0) to Scalar(135.0, 255.0, 240.0), // blue
            Scalar(0.0, 80.0, 40.0) to Scalar(10.0, 255.0, 245.0), // red
            Scalar(165.0, 80.0, 40.0) to Scalar(180.0, 255.0, 245.0), // red, wrapped
            Scalar(40.0, 70.0, 30.0) to Scalar(85.0, 255.0, 235.0), // green
        )
        ranges.forEachIndexed { i, (low, high) ->
            Core.inRange(hsv, low, high, if (i == 0) mask else part)
            if (i > 0) Core.bitwise_or(mask, part, mask)
        }
        // Print: dark and colorless. Where pen crosses a letter, the letter's pixels must survive
        // the growing below.
        val print = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 0.0), Scalar(180.0, 60.0, 140.0), print)
        hsv.release()
        part.release()
        val size = max(3, max(rgb.cols(), rgb.rows()) / 500) or 1
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(size.toDouble(), size.toDouble()))
        // Drop single-pixel color noise, then grow over anti-aliased edges.
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0)))
        Imgproc.dilate(mask, mask, kernel, Point(-1.0, -1.0), 2)
        kernel.release()
        Core.bitwise_not(print, print)
        Core.bitwise_and(mask, print, mask)
        print.release()
        // The outermost strip is where desk or shadow is left after cropping, never handwriting.
        clearMargin(mask, 0.02)
        return mask
    }

    private fun clearMargin(mask: Mat, share: Double) {
        val w = mask.cols()
        val h = mask.rows()
        val band = (max(w, h) * share).toInt().coerceAtLeast(1)
        val zero = Scalar(0.0)
        Imgproc.rectangle(mask, Point(0.0, 0.0), Point(band.toDouble(), h.toDouble()), zero, -1)
        Imgproc.rectangle(mask, Point((w - band).toDouble(), 0.0), Point(w.toDouble(), h.toDouble()), zero, -1)
        Imgproc.rectangle(mask, Point(0.0, 0.0), Point(w.toDouble(), band.toDouble()), zero, -1)
        Imgproc.rectangle(mask, Point(0.0, (h - band).toDouble()), Point(w.toDouble(), h.toDouble()), zero, -1)
    }

    /**
     * Fingers holding a book open: skin tones (a YCrCb range that covers light to dark skin)
     * forming a shape that reaches in from the left, right or bottom edge.
     */
    private fun fingers(rgb: Mat): Mat {
        val ycrcb = Mat()
        Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
        val skin = Mat()
        Core.inRange(ycrcb, Scalar(0.0, 135.0, 85.0), Scalar(255.0, 175.0, 135.0), skin)
        ycrcb.release()
        val open = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        Imgproc.morphologyEx(skin, skin, Imgproc.MORPH_OPEN, open)
        open.release()

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(skin, labels, stats, centroids, 8, CvType.CV_32S)
        val w = rgb.cols()
        val h = rgb.rows()
        val band = (max(w, h) * 0.03).toInt()
        val minArea = w * h * 0.002
        val result = Mat.zeros(h, w, CvType.CV_8UC1)
        val one = Mat()
        // Label 0 is the background; only the largest few shapes matter.
        (1 until count)
            .map { it to stats.get(it, Imgproc.CC_STAT_AREA)[0] }
            .filter { it.second >= minArea }
            .sortedByDescending { it.second }
            .take(6)
            .forEach { (label, _) ->
                val x = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt()
                val bw = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val bh = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val atEdge = x <= band || x + bw >= w - band || y + bh >= h - band
                if (atEdge) {
                    Core.compare(labels, Scalar(label.toDouble()), one, Core.CMP_EQ)
                    Core.bitwise_or(result, one, result)
                }
            }
        listOf(skin, labels, stats, centroids, one).forEach(Mat::release)
        val grow = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.dilate(result, result, grow)
        grow.release()
        return result
    }

    /** Paints brush [strokes] (page coordinates, width as a share of page width) into [mask]. */
    private fun draw(mask: Mat, strokes: List<InkMark>, value: Double) {
        val w = mask.cols().toDouble()
        val h = mask.rows().toDouble()
        val color = Scalar(value)
        for (stroke in strokes) {
            val thickness = max(1, (stroke.width * w).toInt())
            val points = stroke.points.map { Point(it.x * w, it.y * h) }
            if (points.size == 1) {
                Imgproc.circle(mask, points[0], thickness / 2, color, -1)
            } else {
                points.zipWithNext { a, b -> Imgproc.line(mask, a, b, color, thickness, Imgproc.LINE_8) }
            }
        }
    }
}
