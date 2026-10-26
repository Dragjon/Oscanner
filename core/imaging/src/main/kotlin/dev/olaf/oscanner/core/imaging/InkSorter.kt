package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Area
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

/**
 * Tells handwriting from print by the ink itself. Ballpoint and gel pens are a different color
 * from printer toner even when both look black: on a scan the difference is a few units of
 * chroma, but it holds across a page. The print's color is learned from what is surely print
 * (ruled lines and words the text reader is sure of); the pen's from the marks farthest from it.
 * Each mark is then judged by the ink around it, since writing comes in words and lines and a
 * dot or a letter's tail alone has too few pixels to tell, and a line or region of writing that is
 * mostly pen is taken as pen throughout. Ruled lines stay, mended where writing crossed them.
 */
internal object InkSorter {

    /**
     * Pen to [remove], found by its color (none when pen and print are one color); the page's
     * [rules], and where they would run on under writing ([bridged]), drawn in [lineColor];
     * and whether the page is [blank] apart from its rules.
     */
    class Result(val remove: Mat, val rules: Mat, val bridged: Mat, val lineColor: Scalar, val blank: Boolean = false)

    /**
     * Null when the page's inks can't be told apart by color (black pen on black print): the
     * caller falls back to telling them apart by layout.
     */
    fun handwriting(rgb: Mat, printed: List<Area>): Result? {
        val w = rgb.cols()
        val h = rgb.rows()
        val side = max(w, h)
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val labImage = Mat()
        Imgproc.cvtColor(rgb, labImage, Imgproc.COLOR_RGB2Lab)
        val lab = ByteArray(w * h * 3).also { labImage.get(0, 0, it) }
        labImage.release()
        fun l(i: Int) = lab[i * 3].toInt() and 0xFF
        fun a(i: Int) = (lab[i * 3 + 1].toInt() and 0xFF) - 128
        fun b(i: Int) = (lab[i * 3 + 2].toInt() and 0xFF) - 128

        val block = max(15.0, (side / 40).toDouble()).toInt() or 1
        val ink = Mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, block, 12.0)
        // Rules found on a more sensitive threshold: a faint rule is still one, and must not be
        // wiped where writing touches it.
        val faint = Mat()
        Imgproc.adaptiveThreshold(gray, faint, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, block, 5.0)
        // Dense writing darkens the neighborhood a local threshold compares against, until a thin
        // rule through it no longer stands out; a threshold for the whole page still sees it.
        // Against the paper's own brightness, so a shadow across the page isn't all "dark".
        val dark = Mat()
        val paper = Filters.backgroundOf(gray)
        val even = Mat()
        Core.divide(gray, paper, even, 255.0)
        paper.release()
        Imgproc.threshold(even, dark, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        even.release()
        Core.bitwise_or(faint, dark, faint)
        dark.release()
        // A scanned rule is rarely level: it drifts a pixel or two over a hand's width, and on a
        // photo of bent paper it slopes. Long runs of ink near level or upright (see [straight])
        // mark where the rules are.
        val band = straight(faint, max(15, (side * 0.06).toInt()))
        // That band also holds the stubs of strokes that cross the rule. The rule itself is just
        // the thin pieces of ink along it, near level (or upright); the stubs are marks like any other.
        val zone = Mat()
        Imgproc.dilate(band, zone, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        band.release()
        val rules = straight(faint, max(9, (side * 0.02).toInt()))
        Core.bitwise_and(rules, zone, rules)
        // Where the rule ran under writing: bridged along its length, inside its band.
        val bridged = Mat()
        val across = Mat()
        Imgproc.morphologyEx(rules, bridged, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(side * 0.04, 1.0)))
        Imgproc.morphologyEx(rules, across, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, side * 0.04)))
        Core.bitwise_or(bridged, across, bridged)
        Core.bitwise_and(bridged, zone, bridged)
        // The lines to learn print's color from: rules with paper on both sides. The edge of a
        // photo or a colored band is a long straight run too, with the picture on one side, and
        // its color is the picture's, not the print's.
        val reference = lined(rules, gray, max(4, side / 300))
        gray.release()
        listOf(faint, zone, across).forEach(Mat::release)
        val marks = Mat()
        val rulesWide = Mat()
        Imgproc.dilate(rules, rulesWide, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        Core.subtract(ink, rulesWide, marks)
        ink.release()

        // Print's color: the darker half of the rules' pixels. Ink inside words the reader is sure
        // of only helps when there are too few rules: it can be sure of neat handwriting too, and
        // on a ruled page that's mostly what the words are.
        val rulePx = ByteArray(w * h).also { reference.get(0, 0, it) }
        reference.release()
        val markPx = ByteArray(w * h).also { marks.get(0, 0, it) }
        val ruleL = (0 until w * h).filter { rulePx[it] != 0.toByte() }.map { l(it) }.sorted()
        val darkRule = ruleL.getOrNull(ruleL.size / 2) ?: 0
        val boxes = printed.map { area ->
            val x0 = (area.left * w).toInt().coerceIn(0, w - 1)
            val x1 = (area.right * w).toInt().coerceIn(0, w - 1)
            val y0 = (area.top * h).toInt().coerceIn(0, h - 1)
            val y1 = (area.bottom * h).toInt().coerceIn(0, h - 1)
            (y0..y1).flatMap { y -> (x0..x1).map { x -> y * w + x } }.toIntArray()
        }
        val ruled = ruleL.size / 2 >= MIN_REFERENCE
        val inWord = BooleanArray(w * h)
        if (!ruled) for (box in boxes) for (i in box) inWord[i] = true
        val refA = ArrayList<Int>()
        val refB = ArrayList<Int>()
        for (i in 0 until w * h) {
            val isRef = (rulePx[i] != 0.toByte() && l(i) <= darkRule) || (inWord[i] && markPx[i] != 0.toByte())
            if (isRef) {
                refA += a(i)
                refB += b(i)
            }
        }
        if (refA.size < MIN_REFERENCE) {
            listOf(rules, rulesWide, marks, bridged).forEach(Mat::release)
            return null
        }
        val printA = refA.sorted()[refA.size / 2].toDouble()
        val printB = refB.sorted()[refB.size / 2].toDouble()

        // Each mark's color is the average of the ink around it.
        val win = (max(9.0, side * 0.025).toInt() or 1).toDouble()
        val m = Mat()
        marks.convertTo(m, CvType.CV_32F, 1.0 / 255)
        val aMat = Mat(h, w, CvType.CV_32F)
        val bMat = Mat(h, w, CvType.CV_32F)
        aMat.put(0, 0, FloatArray(w * h) { a(it).toFloat() * (markPx[it].toInt() and 1) })
        bMat.put(0, 0, FloatArray(w * h) { b(it).toFloat() * (markPx[it].toInt() and 1) })
        val wSum = Mat()
        Imgproc.boxFilter(m, wSum, -1, Size(win, win), Point(-1.0, -1.0), false)
        Imgproc.boxFilter(aMat, aMat, -1, Size(win, win), Point(-1.0, -1.0), false)
        Imgproc.boxFilter(bMat, bMat, -1, Size(win, win), Point(-1.0, -1.0), false)
        val ws = FloatArray(w * h).also { wSum.get(0, 0, it) }
        val la = FloatArray(w * h).also { aMat.get(0, 0, it) }
        val lb = FloatArray(w * h).also { bMat.get(0, 0, it) }
        listOf(aMat, bMat, wSum).forEach(Mat::release)

        val labelMat = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(marks, labelMat, stats, centroids, 8, CvType.CV_32S)
        centroids.release()
        val labels = IntArray(w * h).also { labelMat.get(0, 0, it) }
        labelMat.release()
        val sumA = DoubleArray(count)
        val sumB = DoubleArray(count)
        val size = IntArray(count)
        for (i in 0 until w * h) {
            val k = labels[i]
            if (k == 0) continue
            val n = max(ws[i], 1f)
            sumA[k] += la[i] / n
            sumB[k] += lb[i] / n
            size[k]++
        }
        val ca = DoubleArray(count) { if (size[it] > 0) sumA[it] / size[it] else 0.0 }
        val cb = DoubleArray(count) { if (size[it] > 0) sumB[it] / size[it] else 0.0 }
        val considered = (1 until count).filter { size[it] >= MIN_MARK }
        val consideredSet = considered.toHashSet()

        // The pen: start at the mark farthest from print, settle on the marks nearer it than to
        // print. Print stays where the reference put it; a page of writing would pull it over.
        val far = considered.maxByOrNull { hypot(ca[it] - printA, cb[it] - printB) * if (size[it] >= 20) 1 else 0 }
        if (far == null) {
            listOf(rulesWide, marks).forEach(Mat::release)
            return Result(Mat.zeros(h, w, CvType.CV_8UC1), rules, bridged, Core.mean(rgb, rules))
        }
        var penA = ca[far]
        var penB = cb[far]
        var pen = BooleanArray(count)
        repeat(10) {
            pen = BooleanArray(count) { k -> k in consideredSet && hypot(ca[k] - penA, cb[k] - penB) < hypot(ca[k] - printA, cb[k] - printB) }
            val penMarks = considered.filter { pen[it] }
            if (penMarks.isEmpty()) return@repeat
            val total = penMarks.sumOf { size[it] }.toDouble()
            penA = penMarks.sumOf { ca[it] * size[it] } / total
            penB = penMarks.sumOf { cb[it] * size[it] } / total
        }
        val gap = hypot(penA - printA, penB - printB)
        // Next to no ink besides the rules: a blank form, nothing written on it.
        if (considered.sumOf { size[it] } < w * h * BLANK_SHARE) {
            listOf(rulesWide, marks).forEach(Mat::release)
            return Result(Mat.zeros(h, w, CvType.CV_8UC1), rules, bridged, Core.mean(rgb, rules), blank = true)
        }
        // Pen and print one color: nothing to tell apart by it, but the rules still stand.
        if (gap < MIN_GAP) {
            listOf(rulesWide, marks).forEach(Mat::release)
            return Result(Mat.zeros(h, w, CvType.CV_8UC1), rules, bridged, Core.mean(rgb, rules))
        }
        // Only words whose marks, taken together, are nearer print's color than the pen's protect
        // what's in them.
        val inPrint = BooleanArray(count)
        for (box in boxes) {
            val inside = box.map { labels[it] }.filter { it != 0 && size[it] > 0 }.distinct()
            val total = inside.sumOf { size[it] }.toDouble()
            if (total == 0.0) continue
            val boxA = inside.sumOf { ca[it] * size[it] } / total
            val boxB = inside.sumOf { cb[it] * size[it] } / total
            // Clearly nearer: a word that could be either is left to its line and neighbors.
            if (hypot(boxA - penA, boxB - penB) - hypot(boxA - printA, boxB - printB) < PRINT_MARGIN * gap) continue
            for (k in inside) inPrint[k] = true
        }

        // Lines of writing: marks smeared sideways, one blob per line.
        val rows = Mat()
        Imgproc.dilate(marks, rows, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(15.0, side * 0.05), 3.0)))
        val rowMat = Mat()
        val rowCount = Imgproc.connectedComponents(rows, rowMat, 8, CvType.CV_32S)
        rows.release()
        val rowOf = IntArray(w * h).also { rowMat.get(0, 0, it) }
        rowMat.release()
        val rowPen = DoubleArray(rowCount)
        val rowAll = DoubleArray(rowCount)
        val markRow = IntArray(count)
        for (i in 0 until w * h) {
            val k = labels[i]
            if (k == 0) continue
            markRow[k] = rowOf[i]
            rowAll[rowOf[i]]++
            if (pen[k]) rowPen[rowOf[i]]++
        }
        // And the region around: a mark amid writing is writing.
        val penPx = Mat(h, w, CvType.CV_32F)
        penPx.put(0, 0, FloatArray(w * h) { if (labels[it] != 0 && pen[labels[it]]) 1f else 0f })
        val area = (side * 0.12).toInt() or 1
        val regionPen = Mat()
        val regionAll = Mat()
        Imgproc.boxFilter(penPx, regionPen, -1, Size(area.toDouble(), area.toDouble()), Point(-1.0, -1.0), false)
        Imgproc.boxFilter(m, regionAll, -1, Size(area.toDouble(), area.toDouble()), Point(-1.0, -1.0), false)
        val rp = FloatArray(w * h).also { regionPen.get(0, 0, it) }
        val ra = FloatArray(w * h).also { regionAll.get(0, 0, it) }
        listOf(penPx, regionPen, regionAll, m).forEach(Mat::release)

        // Marks too small to judge by their own color go with their line and area.
        val isPen = BooleanArray(count)
        for (k in 1 until count) {
            val x = stats.get(k, Imgproc.CC_STAT_LEFT)[0].toInt() + stats.get(k, Imgproc.CC_STAT_WIDTH)[0].toInt() / 2
            val y = stats.get(k, Imgproc.CC_STAT_TOP)[0].toInt() + stats.get(k, Imgproc.CC_STAT_HEIGHT)[0].toInt() / 2
            val c = y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)
            val around = rp[c] / max(ra[c], 1f)
            val rowShare = rowPen[markRow[k]] / max(rowAll[markRow[k]], 1.0)
            // Ink inside a word the reader is sure is print stays, whatever is around it, unless
            // its own color is the pen's: neat handwriting can read as print, but it's still pen.
            isPen[k] = pen[k] || (!inPrint[k] && (rowShare > ROW_SHARE || around > REGION_SHARE))
        }
        stats.release()

        val remove = Mat(h, w, CvType.CV_8UC1)
        val keep = Mat(h, w, CvType.CV_8UC1)
        remove.put(0, 0, ByteArray(w * h) { if (labels[it] != 0 && isPen[labels[it]]) -1 else 0 })
        keep.put(0, 0, ByteArray(w * h) { if (labels[it] != 0 && !isPen[labels[it]] && size[labels[it]] >= MIN_MARK) -1 else 0 })
        val grow = (max(5, side / 300) or 1).toDouble()
        Imgproc.dilate(remove, remove, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(grow, grow)), Point(-1.0, -1.0), 2)
        // Print and rules stay, with a pixel of margin.
        Imgproc.dilate(keep, keep, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        Core.bitwise_or(keep, rules, keep)
        Core.bitwise_not(keep, keep)
        Core.bitwise_and(remove, keep, remove)
        listOf(keep, rulesWide, marks).forEach(Mat::release)
        return Result(remove, rules, bridged, Core.mean(rgb, rules))
    }

    /**
     * Pixels of [rules] darker by [RULE_CONTRAST] levels of [gray] than the page [d] pixels
     * above and below, or to the left and right: a line, not the edge of something.
     */
    /**
     * The ink of [ink] in straight runs at least [length] long, within [TILT] degrees of level or
     * upright. The ink is thickened a little first: a thin line sloping across pixels steps.
     */
    private fun straight(ink: Mat, length: Int): Mat {
        val thick = Mat()
        Imgproc.dilate(ink, thick, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val found = Mat.zeros(ink.size(), CvType.CV_8UC1)
        val part = Mat()
        for (upright in listOf(0.0, 90.0)) {
            var degrees = upright - TILT
            while (degrees <= upright + TILT) {
                val kernel = PenLines.lineKernel(length or 1, degrees)
                Imgproc.morphologyEx(thick, part, Imgproc.MORPH_OPEN, kernel)
                Core.bitwise_or(found, part, found)
                kernel.release()
                degrees += TILT_STEP
            }
        }
        Core.bitwise_and(found, ink, found)
        listOf(thick, part).forEach(Mat::release)
        return found
    }

    private fun lined(rules: Mat, gray: Mat, d: Int): Mat {
        val w = rules.cols()
        val h = rules.rows()
        // How much lighter the page is (dx, dy) away than here; 0 where it's darker.
        fun lighter(dx: Int, dy: Int): Mat {
            val out = Mat.zeros(h, w, CvType.CV_8UC1)
            if (d >= w || d >= h) return out
            val there = gray.submat(maxOf(0, dy), h + minOf(0, dy), maxOf(0, dx), w + minOf(0, dx))
            val here = gray.submat(maxOf(0, -dy), h + minOf(0, -dy), maxOf(0, -dx), w + minOf(0, -dx))
            val diff = out.submat(maxOf(0, -dy), h + minOf(0, -dy), maxOf(0, -dx), w + minOf(0, -dx))
            Core.subtract(there, here, diff)
            listOf(there, here, diff).forEach(Mat::release)
            Imgproc.threshold(out, out, RULE_CONTRAST, 255.0, Imgproc.THRESH_BINARY)
            return out
        }
        fun both(a: Mat, b: Mat) = a.also { Core.bitwise_and(a, b, a); b.release() }
        val across = both(lighter(0, -d), lighter(0, d))
        val along = both(lighter(-d, 0), lighter(d, 0))
        Core.bitwise_or(across, along, across)
        Core.bitwise_and(across, rules, across)
        along.release()
        return across
    }

    /** A rule is this much darker (gray levels) than the page on either side of it. */
    private const val RULE_CONTRAST = 15.0
    /** Fewer surely-print pixels than this and the print's color isn't known well enough. */
    private const val MIN_REFERENCE = 200
    /** Marks smaller than this (pixels) are too small to judge by their own color. */
    private const val MIN_MARK = 6
    /** Print and pen closer than this in chroma can't be told apart by color. */
    private const val MIN_GAP = 2.0
    /** How much nearer print than pen, as a share of the gap between them, a sure word must be. */
    private const val PRINT_MARGIN = 0.3
    /** Rules are looked for this many degrees either side of level and upright, in steps of [TILT_STEP]. */
    private const val TILT = 10.0
    private const val TILT_STEP = 2.5

    /** Less ink than this share of the page, rules aside, is a blank page. */
    private const val BLANK_SHARE = 0.002
    private const val ROW_SHARE = 0.35
    private const val REGION_SHARE = 0.5
}
