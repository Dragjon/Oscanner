package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Area
import dev.olaf.oscanner.core.model.InkMark
import dev.olaf.oscanner.core.model.PageEdits
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Removing things from a straightened page (PLAN.md §5.5, the classical tier). Everything to
 * remove becomes one mask, filled with blank paper taken from around it. Works on 8-bit RGB,
 * before the look is applied.
 */
internal object Cleanup {

    /** Fills what [edits] removes, in place. */
    fun apply(rgb: Mat, edits: PageEdits) {
        val mask = mask(rgb, edits) ?: return
        val paper = Filters.backgroundOf(rgb)
        fillFromAround(paper, mask)
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
        if (edits.removePenInk) {
            penInk(rgb).also { Core.bitwise_or(mask, it, mask); it.release() }
            edits.printed?.let { printed -> darkHandwriting(rgb, printed).also { Core.bitwise_or(mask, it, mask); it.release() } }
        }
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
     * The paper estimate comes from closing away small dark things, which is exact under pen
     * strokes but not under a thumb: that is wider than the kernel, so the "paper" there is the
     * thumb. Masked areas are refilled from the paper around them instead (Telea 2004
     * inpainting, on a small copy: paper changes slowly).
     */
    private fun fillFromAround(paper: Mat, mask: Mat) {
        val scale = min(1.0, FILL_SIDE / max(paper.cols(), paper.rows()))
        val smallPaper = Mat()
        val smallMask = Mat()
        Imgproc.resize(paper, smallPaper, Size(), scale, scale, Imgproc.INTER_AREA)
        Imgproc.resize(mask, smallMask, smallPaper.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.threshold(smallMask, smallMask, 0.0, 255.0, Imgproc.THRESH_BINARY)
        // The closing smears the removed thing's color a little past its outline; take that too.
        val grow = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.dilate(smallMask, smallMask, grow, Point(-1.0, -1.0), 2)
        grow.release()
        val filled = Mat()
        Photo.inpaint(smallPaper, smallMask, filled, 4.0, Photo.INPAINT_TELEA)
        val full = Mat()
        Imgproc.resize(filled, full, paper.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        full.copyTo(paper, mask)
        listOf(smallPaper, smallMask, filled, full).forEach(Mat::release)
    }

    /**
     * Colored pen: blue, red or green strokes with some saturation, including the fainter
     * blue of a ballpoint. Black and gray print has almost none, so it's left alone. Grown a
     * little to catch the strokes' soft edges.
     */
    private fun penInk(rgb: Mat): Mat {
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val mask = Mat()
        val part = Mat()
        // OpenCV hue runs 0–180.
        val ranges = listOf(
            // Near-black ink reads as any hue; below this brightness it's left to the dark-ink check.
            Scalar(95.0, 45.0, 60.0) to Scalar(135.0, 255.0, 235.0), // blue, ballpoint included
            // Red pen is far more saturated than skin, which shares its hue.
            Scalar(0.0, 140.0, 40.0) to Scalar(8.0, 255.0, 245.0), // red
            Scalar(165.0, 140.0, 40.0) to Scalar(180.0, 255.0, 245.0), // red, wrapped
            Scalar(40.0, 70.0, 30.0) to Scalar(85.0, 255.0, 235.0), // green
        )
        ranges.forEachIndexed { i, (low, high) ->
            Core.inRange(hsv, low, high, if (i == 0) mask else part)
            if (i > 0) Core.bitwise_or(mask, part, mask)
        }
        // Print: dark and colorless. Where pen crosses a letter, the letter's pixels must survive
        // the growing below.
        val print = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 0.0), Scalar(180.0, 40.0, 140.0), print)
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

    /**
     * Pencil and dark pen: any ink that isn't inside a [printed] word. The text reader is sure of
     * printed words and unsure of handwriting, so what it read with confidence marks the print.
     * Some ink is neither and stays: long straight rules and table lines, and solid shapes like
     * logos and photos.
     */
    private fun darkHandwriting(rgb: Mat, printed: List<Area>): Mat {
        val w = rgb.cols()
        val h = rgb.rows()
        val side = max(w, h)
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val ink = Mat()
        // Darker than its neighborhood by a clear margin: ink, under any lighting.
        val block = (side / 40) or 1
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, max(15, block), 18.0)
        gray.release()
        // Pencil and dark pen are colorless; skin, stamps and colored print are not. (Colored pen
        // is found by [penInk].)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val colorless = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 0.0), Scalar(180.0, 60.0, 255.0), colorless)
        hsv.release()
        Core.bitwise_and(ink, colorless, ink)
        colorless.release()
        // Straight rules and table lines come out first, so a signature on a signature line takes
        // only itself away.
        val rules = Mat()
        val run = (side * 0.06).coerceAtLeast(15.0)
        val across = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(run, 1.0))
        val down = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, run))
        val part = Mat()
        Imgproc.morphologyEx(ink, rules, Imgproc.MORPH_OPEN, across)
        Imgproc.morphologyEx(ink, part, Imgproc.MORPH_OPEN, down)
        Core.bitwise_or(rules, part, rules)
        Imgproc.dilate(rules, rules, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        Core.subtract(ink, rules, ink)
        listOf(rules, part, across, down).forEach(Mat::release)

        // Print stays: clear ink along every printed line, first word to last. Whole lines rather
        // than words, because a word with pen scribbled across it reads poorly and drops out of
        // the confident ones while its neighbors don't. Notes in the margin beside a line are
        // further out than a word gap and still go.
        val zero = Scalar(0.0)
        for (line in printedLines(printed, w, h)) {
            Imgproc.rectangle(ink, Point(line[0], line[1]), Point(line[2], line[3]), zero, -1)
        }

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
        val result = Mat.zeros(h, w, CvType.CV_8UC1)
        val one = Mat()
        val minArea = (side * 0.0025).let { it * it }
        val longLine = side * 0.15
        val thin = side * 0.008
        val bigBlob = (side * 0.04).let { it * it }
        for (label in 1 until count) {
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0]
            if (area < minArea) continue
            val bw = stats.get(label, Imgproc.CC_STAT_WIDTH)[0]
            val bh = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0]
            // Ruled lines and table borders: long and thin.
            if (max(bw, bh) > longLine && min(bw, bh) < thin) continue
            // Logos, photos, black boxes: big and mostly solid.
            if (bw * bh > bigBlob && area / (bw * bh) > 0.45) continue
            Core.compare(labels, Scalar(label.toDouble()), one, Core.CMP_EQ)
            Core.bitwise_or(result, one, result)
        }
        listOf(ink, labels, stats, centroids, one).forEach(Mat::release)
        // Over the strokes' soft edges, which are lighter than the threshold but still show.
        val reach = (max(5, side / 220) or 1).toDouble()
        val grow = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(reach, reach))
        Imgproc.dilate(result, result, grow, Point(-1.0, -1.0), 2)
        grow.release()
        clearMargin(result, 0.02)
        return result
    }

    /**
     * Printed words grouped into lines (words that overlap vertically by more than half), each as
     * a pixel box [left, top, right, bottom] padded by a word gap sideways and a little up and
     * down for ascenders and descenders.
     */
    internal fun printedLines(printed: List<Area>, w: Int, h: Int): List<DoubleArray> {
        val lines = mutableListOf<MutableList<Area>>()
        for (word in printed.sortedBy { it.top }) {
            val height = word.bottom - word.top
            val line = lines.firstOrNull { line ->
                val top = line.minOf { it.top }
                val bottom = line.maxOf { it.bottom }
                min(bottom, word.bottom) - max(top, word.top) > 0.5f * min(height, bottom - top)
            }
            if (line != null) line += word else lines += mutableListOf(word)
        }
        val boxes = lines.map { line ->
            doubleArrayOf(line.minOf { it.left } * w.toDouble(), line.minOf { it.top } * h.toDouble(), line.maxOf { it.right } * w.toDouble(), line.maxOf { it.bottom } * h.toDouble())
        }
        return boxes.map { box ->
            val height = box[3] - box[1]
            val gap = height * 1.2
            // Lines of a paragraph share its margins: a line whose last words read poorly still
            // reaches as far as the lines just above and below it.
            val near = boxes.filter { abs(it[1] - box[1]) < height * 2.5 }
            doubleArrayOf(
                near.minOf { it[0] } - gap,
                box[1] - height * 0.25,
                near.maxOf { it[2] } + gap,
                box[3] + height * 0.25,
            )
        }
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
     * forming a shape that reaches in from the left, right or bottom edge. Paper under warm light
     * can fall in that range too, so skin must also be redder than this page's own paper.
     */
    private fun fingers(rgb: Mat): Mat {
        val ycrcb = Mat()
        Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
        val paperCr = medianCr(ycrcb)
        val skin = Mat()
        Core.inRange(ycrcb, Scalar(0.0, max(135.0, paperCr + 8.0), 85.0), Scalar(255.0, 175.0, 135.0), skin)
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
        // Label 0 is the background; only the largest few shapes matter. A shape covering most
        // of the page is the page itself, not a hand on it.
        (1 until count)
            .map { it to stats.get(it, Imgproc.CC_STAT_AREA)[0] }
            .filter { it.second >= minArea && it.second < w * h * 0.5 }
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
        // Skin's soft edge and the finger's shadow on the paper.
        val grow = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
        Imgproc.dilate(result, result, grow)
        grow.release()
        return result
    }

    /** The page's typical red-difference: its paper, since paper covers most of it. */
    private fun medianCr(ycrcb: Mat): Double {
        val small = Mat()
        Imgproc.resize(ycrcb, small, Size(64.0, 64.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val channels = mutableListOf<Mat>()
        Core.split(small, channels)
        val values = DoubleArray(64 * 64)
        val cr = channels[1]
        for (y in 0 until 64) for (x in 0 until 64) values[y * 64 + x] = cr.get(y, x)[0]
        small.release()
        channels.forEach(Mat::release)
        values.sort()
        return values[values.size / 2]
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

    private const val FILL_SIDE = 320.0
}
