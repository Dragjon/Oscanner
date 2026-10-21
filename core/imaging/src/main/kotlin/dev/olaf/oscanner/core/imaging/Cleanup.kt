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

    /** Fills what [edits] removes, in place, and mends ruled lines writing crossed. */
    fun apply(rgb: Mat, edits: PageEdits) {
        val (mask, mend) = plan(rgb, edits)
        if (mask != null) {
            val paper = paperUnder(rgb, mask)
            paper.copyTo(rgb, mask)
            paper.release()
            mask.release()
        }
        mend?.let { (where, color) ->
            rgb.setTo(color, where)
            where.release()
        }
    }

    /** 255 where something will be removed, or null when nothing will. */
    fun mask(rgb: Mat, edits: PageEdits): Mat? {
        val (mask, mend) = plan(rgb, edits)
        mend?.first?.release()
        return mask
    }

    /** What to remove, and where to redraw ruled lines in which color. */
    private fun plan(rgb: Mat, edits: PageEdits): Pair<Mat?, Pair<Mat, Scalar>?> {
        if (!edits.cleaned) return null to null
        val w = rgb.cols()
        val h = rgb.rows()
        val mask = Mat.zeros(h, w, CvType.CV_8UC1)
        var mend: Pair<Mat, Scalar>? = null
        if (edits.removePenInk) {
            // Charts, photos and coloured boxes are pen-coloured in places, but no pen draws that thick.
            val solid = solidShapes(rgb)
            penInk(rgb).also { Core.subtract(it, solid, it); Core.bitwise_or(mask, it, mask); it.release() }
            edits.printed?.let { found ->
                // Print found by its look is exact; words the reader was sure of need checking.
                val printed = if (edits.printByLook) found else confidentPrint(rgb, found)
                // Pen of another color than the print goes by its color; black and gray writing,
                // the print's own color, by where it is: outside the lines of print.
                val neutral = withoutHighlights(rgb)
                val sorted = runCatching { InkSorter.handwriting(neutral, printed) }.getOrNull()
                neutral.release()
                val writing = Mat.zeros(h, w, CvType.CV_8UC1)
                sorted?.remove?.let { Core.bitwise_or(writing, it, writing); it.release() }
                if (sorted?.blank != true) {
                    darkHandwriting(rgb, printed, exact = edits.printByLook).also { Core.bitwise_or(writing, it, writing); it.release() }
                }
                // Before the mend, too: a bar's straight edges look like rules the writing crossed.
                Core.subtract(writing, solid, writing)
                sorted?.let { s ->
                    // Rules stay, and are drawn on through the gaps the writing leaves.
                    val near = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                    val wide = Mat()
                    Imgproc.dilate(s.rules, wide, near)
                    Core.subtract(writing, wide, writing)
                    val gaps = Mat()
                    Core.subtract(s.bridged, s.rules, gaps)
                    Imgproc.dilate(writing, wide, near)
                    Core.bitwise_and(gaps, wide, gaps)
                    mend = gaps to s.lineColor
                    listOf(wide, s.rules, s.bridged).forEach(Mat::release)
                }
                Core.bitwise_or(mask, writing, mask)
                writing.release()
            }
            solid.release()
        }
        if (edits.removeFingers) fingers(rgb).also { Core.bitwise_or(mask, it, mask); it.release() }
        if (edits.removeShowThrough) showThrough(rgb).also { Core.bitwise_or(mask, it, mask); it.release() }
        // "Keep" protects from the automatic removals; painting "erase" always wins.
        draw(mask, edits.keep, 0.0)
        draw(mask, edits.erase, 255.0)
        if (Core.countNonZero(mask) == 0) {
            mask.release()
            return null to mend
        }
        return mask to mend
    }

    /**
     * Blank paper to put under [mask], drawn in from the nearest pixels that are clearly paper,
     * with no ink and nothing being removed, so it follows the light across the page. Closing
     * away the ink instead left dense writing's gray behind, like a shadow where the writing had
     * been; and a thumb is wider than any closing. Lighting changes slowly, so it's worked out on
     * a small copy (Telea 2004 inpainting).
     */
    private fun paperUnder(rgb: Mat, mask: Mat): Mat {
        // What isn't paper is found at full size, where even thin strokes stand out; a spot of
        // the small copy is paper only if nothing in it was ink.
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val notPaper = Mat()
        Imgproc.adaptiveThreshold(gray, notPaper, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, max(15, (max(rgb.cols(), rgb.rows()) / 40) or 1), 6.0)
        gray.release()
        Core.bitwise_or(notPaper, mask, notPaper)
        Imgproc.dilate(notPaper, notPaper, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        val scale = min(1.0, FILL_SIDE / max(rgb.cols(), rgb.rows()))
        val small = Mat()
        Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val holes = Mat()
        Imgproc.resize(notPaper, holes, small.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        notPaper.release()
        Imgproc.threshold(holes, holes, 0.0, 255.0, Imgproc.THRESH_BINARY)
        // A page with no clear paper at all keeps its own colors.
        val filled = Mat()
        if (Core.countNonZero(holes) < holes.total()) {
            Photo.inpaint(small, holes, filled, 3.0, Photo.INPAINT_TELEA)
            // The fill can carry a little texture from the hole's edge; paper hasn't any.
            Imgproc.GaussianBlur(filled, filled, Size(5.0, 5.0), 0.0)
        } else {
            small.copyTo(filled)
        }
        listOf(small, holes).forEach(Mat::release)
        val full = Mat()
        Imgproc.resize(filled, full, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        filled.release()
        return full
    }

    /**
     * The reader can be sure of a neat handwritten word too, now and then. Of its sure words,
     * only lines that are mostly sure count as print: on a line of print nearly every word reads
     * well; on a line of handwriting a sure word sits among ones that don't.
     */
    private fun confidentPrint(rgb: Mat, printed: List<Area>): List<Area> {
        if (printed.isEmpty()) return printed
        val w = rgb.cols()
        val h = rgb.rows()
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val inkMat = Mat()
        Imgproc.adaptiveThreshold(gray, inkMat, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, max(15, (max(w, h) / 40) or 1), 18.0)
        gray.release()
        // Rules run under every word; they'd count as covered and uncovered alike.
        val rules = Mat()
        Imgproc.morphologyEx(inkMat, rules, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(w, h) * 0.06, 1.0)))
        Core.subtract(inkMat, rules, inkMat)
        rules.release()
        val ink = ByteArray(w * h).also { inkMat.get(0, 0, it) }
        inkMat.release()
        val kept = mutableListOf<Area>()
        for (line in lineGroups(printed)) {
            val height = (line.maxOf { it.bottom } - line.minOf { it.top }) * h
            val y0 = (line.minOf { it.top } * h).toInt().coerceIn(0, h - 1)
            val y1 = (line.maxOf { it.bottom } * h).toInt().coerceIn(0, h - 1)
            val x0 = (line.minOf { it.left } * w - LINE_REACH * height).toInt().coerceIn(0, w - 1)
            val x1 = (line.maxOf { it.right } * w + LINE_REACH * height).toInt().coerceIn(0, w - 1)
            var all = 0
            var covered = 0
            for (y in y0..y1) for (x in x0..x1) {
                if (ink[y * w + x] == 0.toByte()) continue
                all++
                val nx = (x + 0.5f) / w
                val ny = (y + 0.5f) / h
                if (line.any { nx >= it.left && nx <= it.right && ny >= it.top && ny <= it.bottom }) covered++
            }
            // A run of print is long; the handwriting it's sure of is a short word or two. A short
            // label ("Class:") counts only when almost all of its line is sure.
            val run = line.sumOf { ((it.right - it.left) * w).toDouble() } / height
            val cover = if (all == 0) 1.0 else covered.toDouble() / all
            if ((cover >= SURE_LINE && run >= LONG_RUN) || (cover >= SURE_LABEL && run >= LABEL_RUN)) kept += line
        }
        return kept
    }

    /** Words that overlap vertically by more than half, as lines. */
    private fun lineGroups(words: List<Area>): List<List<Area>> {
        val lines = mutableListOf<MutableList<Area>>()
        for (word in words.sortedBy { it.top }) {
            val height = word.bottom - word.top
            val line = lines.firstOrNull { line ->
                val top = line.minOf { it.top }
                val bottom = line.maxOf { it.bottom }
                min(bottom, word.bottom) - max(top, word.top) > 0.5f * min(height, bottom - top)
            }
            if (line != null) line += word else lines += mutableListOf(word)
        }
        return lines
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
     * [rgb] with the color taken out of highlighted areas (light and strongly colored), for
     * telling pen from print by color: letters under a highlighter blend into it at their edges
     * and would look like ink of another color than the print. Pen inside a highlight is still
     * found by where it is, outside the lines of print.
     */
    private fun withoutHighlights(rgb: Mat): Mat {
        // Light in brightness as the eye sees it, which colored pen isn't, however vivid.
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val marked = Mat()
        Core.extractChannel(hsv, marked, 1)
        hsv.release()
        Imgproc.threshold(marked, marked, HIGHLIGHT_SATURATION, 255.0, Imgproc.THRESH_BINARY)
        val light = Mat()
        Imgproc.cvtColor(rgb, light, Imgproc.COLOR_RGB2GRAY)
        Imgproc.threshold(light, light, HIGHLIGHT_LIGHTNESS, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_and(marked, light, marked)
        light.release()
        // Whole stripes, letters and their soft edges included.
        val side = max(rgb.cols(), rgb.rows())
        val close = max(9.0, side * 0.012)
        Imgproc.morphologyEx(marked, marked, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(close, close)))
        Imgproc.dilate(marked, marked, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        val out = rgb.clone()
        if (Core.countNonZero(marked) > 0) {
            val gray = Mat()
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.cvtColor(gray, gray, Imgproc.COLOR_GRAY2RGB)
            gray.copyTo(out, marked)
            gray.release()
        }
        marked.release()
        return out
    }

    /**
     * The back page's print seen through thin paper: marks far lighter than the print, gray, and
     * soft-edged, because the paper blurs them on the way through. Faint ink on the front, like
     * pencil, has sharp edges; highlighter and colored print are saturated; and a photo's pale
     * parts are broad areas, not marks the size of letters, so all of those stay.
     */
    private fun showThrough(rgb: Mat): Mat {
        val w = rgb.cols()
        val h = rgb.rows()
        val side = max(w, h)
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val paper = Filters.backgroundOf(gray)
        val even = Mat()
        Core.divide(gray, paper, even, 255.0)
        gray.release()

        // Faint: off the paper, but far lighter than print. Judged with the grain smoothed out:
        // show-through is smooth, grain is not.
        val faint = Mat()
        Imgproc.medianBlur(even, faint, 5)
        Core.inRange(faint, Scalar(SHOW_DARKEST + 1), Scalar(paperFloor(faint) - 1), faint)
        // Print, and its soft edge.
        val near = Mat()
        Imgproc.threshold(even, near, SHOW_DARKEST, 255.0, Imgproc.THRESH_BINARY_INV)
        val reach = (max(3, side / 400) * 2 + 1).toDouble()
        Imgproc.dilate(near, near, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(reach, reach)))
        Core.subtract(faint, near, faint)
        // Sharp steps are ink on the front.
        val soft = Mat()
        Imgproc.GaussianBlur(even, soft, Size(3.0, 3.0), 0.0)
        Imgproc.morphologyEx(soft, soft, Imgproc.MORPH_GRADIENT, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        Imgproc.threshold(soft, near, SHOW_STEP, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.dilate(near, near, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        Core.subtract(faint, near, faint)
        listOf(soft, even).forEach(Mat::release)
        // Colored marks: highlighter, colored print.
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        Core.extractChannel(hsv, near, 1)
        hsv.release()
        Imgproc.threshold(near, near, SHOW_SATURATION, 255.0, Imgproc.THRESH_BINARY)
        Core.subtract(faint, near, faint)
        Imgproc.morphologyEx(faint, faint, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0)))

        // Letter-sized marks only: grain and the fringes of pen strokes are specks, and blurred
        // letters run together into words. Around anything broad (a photo's pale part), small
        // pieces are part of it too; and where the paper estimate itself sinks below the paper
        // further out, something big sits there and its rim looks faint.
        val labelMat = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(faint, labelMat, stats, centroids, 8, CvType.CV_32S)
        val labels = IntArray(w * h).also { labelMat.get(0, 0, it) }
        val big = side * SHOW_MARK
        val least = (side * SHOW_SPECK).let { it * it }
        // 1: a mark to remove; 2: something broad to keep clear of.
        val kind = ByteArray(count) { k ->
            when {
                k == 0 -> 0
                min(stats.get(k, Imgproc.CC_STAT_WIDTH)[0], stats.get(k, Imgproc.CC_STAT_HEIGHT)[0]) >= big -> 2
                stats.get(k, Imgproc.CC_STAT_AREA)[0] >= least -> 1
                else -> 0
            }
        }
        listOf(labelMat, stats, centroids).forEach(Mat::release)
        faint.put(0, 0, ByteArray(w * h) { if (kind[labels[it]] == 1.toByte()) -1 else 0 })
        val broad = Mat(h, w, CvType.CV_8UC1)
        broad.put(0, 0, ByteArray(w * h) { if (kind[labels[it]] == 2.toByte()) -1 else 0 })
        val scale = min(1.0, 400.0 / side)
        val small = Mat()
        Imgproc.resize(paper, small, Size(), scale, scale, Imgproc.INTER_AREA)
        paper.release()
        val wide = Mat()
        val k = ((max(small.cols(), small.rows()) / 5).coerceAtLeast(31) or 1).toDouble()
        Imgproc.morphologyEx(small, wide, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k, k)))
        Core.multiply(small, Scalar(1.0 / SHOW_SUNK), small)
        Core.compare(small, wide, wide, Core.CMP_LT)
        small.release()
        Imgproc.resize(wide, near, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        wide.release()
        Core.bitwise_or(broad, near, broad)
        val margin = (big.toInt() or 1).toDouble()
        Imgproc.dilate(broad, broad, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(margin, margin)))
        Core.subtract(faint, broad, faint)
        listOf(broad, near).forEach(Mat::release)
        Imgproc.dilate(faint, faint, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        return faint
    }

    /**
     * The lightest a mark can be and still stand out from the paper on flattened [even] (paper at
     * about 255): below the paper's own blotchiness on a grainy photo, which would otherwise read
     * as faint marks. The paper's level and spread are its median and median deviation, which the
     * page's text and show-through hardly move.
     */
    private fun paperFloor(even: Mat): Double {
        val bytes = ByteArray(even.total().toInt()).also { even.get(0, 0, it) }
        val counts = IntArray(256)
        for (v in bytes) counts[v.toInt() and 0xFF]++
        fun median(from: Int, to: Int, value: (Int) -> Int): Int {
            val spread = IntArray(256)
            for (v in from..to) spread[value(v)] += counts[v]
            val half = spread.sum() / 2
            var seen = 0
            spread.forEachIndexed { i, n -> seen += n; if (seen > half) return i }
            return 0
        }
        val level = median(PAPER_FROM, 255) { it }
        val deviation = median(PAPER_FROM, 255) { abs(it - level) }
        return min(SHOW_LIGHTEST, level - SHOW_SPREAD * 1.4826 * max(deviation, 1))
    }

    /**
     * Filled areas wider every way than a pen or marker stroke: chart bars, photos, coloured
     * boxes, solid logos. Ink is anything clearly coloured or darker than the paper around it;
     * what an opening wider than a stroke leaves of it is solid. Grown to take in the shapes'
     * edges, which the removals see as thin marks of their own.
     */
    private fun solidShapes(rgb: Mat): Mat {
        val side = max(rgb.cols(), rgb.rows())
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val ink = Mat()
        Core.extractChannel(hsv, ink, 1)
        hsv.release()
        Imgproc.threshold(ink, ink, SOLID_SATURATION, 255.0, Imgproc.THRESH_BINARY)
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val paper = Filters.backgroundOf(gray)
        Core.divide(gray, paper, gray, 255.0)
        paper.release()
        val dark = Mat()
        Imgproc.threshold(gray, dark, SOLID_DARK, 255.0, Imgproc.THRESH_BINARY_INV)
        gray.release()
        Core.bitwise_or(ink, dark, ink)
        dark.release()
        val stroke = max(9.0, side * SOLID_STROKE)
        Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(stroke, stroke)))
        // A fat marker's letters leave crumbs about a stroke wide; a picture is a few strokes each way.
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
        val least = stroke * SOLID_PIECE
        for (k in 1 until count) {
            if (stats.get(k, Imgproc.CC_STAT_WIDTH)[0] < least || stats.get(k, Imgproc.CC_STAT_HEIGHT)[0] < least) {
                val crumb = Mat()
                Core.compare(labels, Scalar(k.toDouble()), crumb, Core.CMP_EQ)
                ink.setTo(Scalar(0.0), crumb)
                crumb.release()
            }
        }
        listOf(labels, stats, centroids).forEach(Mat::release)
        // As far again: the opening rounds the shapes' corners off by about that much.
        Imgproc.dilate(ink, ink, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(stroke, stroke)))
        return ink
    }

    /**
     * Pencil and dark pen: any ink that isn't inside a [printed] word. The text reader is sure of
     * printed words and unsure of handwriting, so what it read with confidence marks the print.
     * Some ink is neither and stays: long straight rules and table lines, and solid shapes like
     * logos and photos.
     */
    private fun darkHandwriting(rgb: Mat, printed: List<Area>, exact: Boolean): Mat {
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
        // Any color: pen in shadow is too dark to tell by its color, but it's still outside the
        // print. Logos, photos and skin are big shapes, kept below.
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
        if (exact) {
            // Each stretch of printed marks, with a pixel or two for the strokes' soft edges.
            val pad = max(2.0, side * 0.002)
            for (a in printed) {
                Imgproc.rectangle(ink, Point(a.left * w - pad, a.top * h - pad), Point(a.right * w + pad, a.bottom * h + pad), zero, -1)
            }
        } else {
            for (line in printedLines(printed, w, h)) {
                Imgproc.rectangle(ink, Point(line[0], line[1]), Point(line[2], line[3]), zero, -1)
            }
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
        // Writing comes in marks about a line high; a crest or a drawing stands taller.
        val heights = (1 until count).filter { stats.get(it, Imgproc.CC_STAT_AREA)[0] >= minArea }
            .map { stats.get(it, Imgproc.CC_STAT_HEIGHT)[0] }.sorted()
        val tall = max(side * 0.04, 3.0 * (heights.getOrNull(heights.size / 2) ?: 0.0))
        // A crest or a drawing: big both ways, or big and solid. What's inside one is part of it,
        // however small its pieces.
        val pictures = (1 until count).filter { label ->
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0]
            val bw = stats.get(label, Imgproc.CC_STAT_WIDTH)[0]
            val bh = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0]
            (bh > tall && bw > tall) || (bw * bh > bigBlob && area / (bw * bh) > 0.45)
        }.map { label ->
            val x = stats.get(label, Imgproc.CC_STAT_LEFT)[0]
            val y = stats.get(label, Imgproc.CC_STAT_TOP)[0]
            doubleArrayOf(x, y, x + stats.get(label, Imgproc.CC_STAT_WIDTH)[0], y + stats.get(label, Imgproc.CC_STAT_HEIGHT)[0])
        }
        for (label in 1 until count) {
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0]
            if (area < minArea) continue
            val bw = stats.get(label, Imgproc.CC_STAT_WIDTH)[0]
            val bh = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0]
            val x = stats.get(label, Imgproc.CC_STAT_LEFT)[0]
            val y = stats.get(label, Imgproc.CC_STAT_TOP)[0]
            if (pictures.any { p -> x >= p[0] && y >= p[1] && x + bw <= p[2] && y + bh <= p[3] }) continue
            // Ruled lines and table borders: long and thin.
            if (max(bw, bh) > longLine && min(bw, bh) < thin) continue
            // Logos, photos, black boxes: big and mostly solid; crests and drawings: tall.
            if (bw * bh > bigBlob && area / (bw * bh) > 0.45) continue
            if (bh > tall) continue
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
        val boxes = lineGroups(printed).map { line ->
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
    /** Highlighter: at least this saturated (HSV, 0-255) and this light (gray level). */
    private const val HIGHLIGHT_SATURATION = 70.0
    private const val HIGHLIGHT_LIGHTNESS = 170.0
    /** How far past a line's sure words, in line heights, its other words are looked for. */
    private const val LINE_REACH = 2.0
    /** The share of a line's ink inside sure words for it to count as print... */
    private const val SURE_LINE = 0.6
    /** ...when its sure words run this many line heights; */
    private const val LONG_RUN = 6.0
    /** or for a short label of this many line heights, this share. */
    private const val SURE_LABEL = 0.9
    private const val LABEL_RUN = 2.5
    /** Solid shapes: saturation, or brightness against the paper (255 = paper), that counts as ink... */
    private const val SOLID_SATURATION = 60.0
    private const val SOLID_DARK = 200.0
    /** ...filled this share of the page's long side every way: wider than a marker's stroke. */
    private const val SOLID_STROKE = 0.01
    /** Show-through: lighter than this on flattened paper (255) and darker than [SHOW_LIGHTEST]... */
    private const val SHOW_DARKEST = 150.0
    private const val SHOW_LIGHTEST = 247.0
    /** ...and further below the paper than this many of its own noise's standard deviations. */
    private const val SHOW_SPREAD = 4.0
    /** Flattened values from here up are paper for measuring its noise. */
    private const val PAPER_FROM = 200
    /** ...with no step sharper than this (levels across three pixels), and grayer than this. */
    private const val SHOW_STEP = 22.0
    private const val SHOW_SATURATION = 40.0
    /** A mark this share of the page's long side, both ways, is no letter; under this share squared, a speck. */
    private const val SHOW_MARK = 0.03
    private const val SHOW_SPECK = 0.004
    /** The paper estimate this far below the paper further out has something big under it. */
    private const val SHOW_SUNK = 0.9
    /** A solid shape is at least this many of those wide and tall. */
    private const val SOLID_PIECE = 3.0
}
