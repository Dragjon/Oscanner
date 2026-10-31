package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.PageFilter
import dev.olaf.oscanner.core.model.Area
import dev.olaf.oscanner.core.model.InkMark
import dev.olaf.oscanner.core.model.PageEdits
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Removing things from a straightened page (PLAN.md §5.5, the classical tier). Everything to
 * remove becomes one mask, filled with blank paper taken from around it. Works on 8-bit RGB,
 * before the look is applied.
 */
internal object Cleanup {

    /**
     * Fills what [edits] removes, in place, and mends ruled lines writing crossed. [desk] is the
     * colors around the crop in the photo, when known: on a photo, edges are mended from it.
     */
    fun apply(rgb: Mat, edits: PageEdits, desk: List<Scalar>? = null) {
        // Stains are taken out first: what's under them is kept, and then judged like the rest.
        if (edits.removeStains) unstain(rgb)
        if (edits.fillEdges) {
            // A photo's torn corners are filled with more picture; a page's with paper.
            if (edits.filter == PageFilter.Photo) desk?.let { OldPhoto.mendTorn(rgb, it) } else fillEdges(rgb)
        }
        val (mask, mend) = plan(rgb, edits)
        if (mask != null) {
            // On a photo, what's taken out (a stain brushed over) is filled with more of the
            // picture; on a page, with the paper.
            if (edits.filter == PageFilter.Photo) {
                PatchFill.fill(rgb, mask)
            } else {
                val paper = paperUnder(rgb, mask)
                val rim = rimAround(rgb, mask)
                paper.copyTo(rgb, mask)
                // Lifted to the paper's shade, never darkened: what's there is the rim's gray.
                val lifted = Mat()
                Core.max(rgb, paper, lifted)
                lifted.copyTo(rgb, rim)
                listOf(paper, rim, lifted).forEach(Mat::release)
            }
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
        // The handwriting network, when it's there, sees pen, pencil and print together and
        // decides alone; the rules below (ink color, print boxes, rules and underlines) are kept
        // for when it isn't.
        val hand = if (edits.removePenInk) HandMask.of(rgb) else null
        if (hand != null) {
            // Charts, photos and coloured boxes: no pen draws that thick.
            val solid = solidShapes(rgb)
            handwriting(rgb, hand, edits.printed?.takeIf { edits.printByLook }).also { Core.subtract(it, solid, it); Core.bitwise_or(mask, it, mask); it.release() }
            listOf(hand, solid).forEach(Mat::release)
        } else if (edits.removePenInk) {
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
                // Lines drawn under the print go even where they touch the words. Near enough
                // straight, they'd pass for rules: they aren't kept as ones, or drawn on.
                val drawn = if (sorted?.blank != true) underlines(rgb, solid) else Mat.zeros(h, w, CvType.CV_8UC1)
                Core.subtract(drawn, solid, drawn)
                sorted?.let { s ->
                    // Rules stay, and are drawn on through the gaps the writing leaves.
                    val near = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                    val wide = Mat()
                    Imgproc.dilate(drawn, wide, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)))
                    Core.subtract(s.rules, wide, s.rules)
                    Core.subtract(s.bridged, wide, s.bridged)
                    Imgproc.dilate(s.rules, wide, near)
                    Core.subtract(writing, wide, writing)
                    val gaps = Mat()
                    Core.subtract(s.bridged, s.rules, gaps)
                    Imgproc.dilate(writing, wide, near)
                    Core.bitwise_and(gaps, wide, gaps)
                    mend = gaps to s.lineColor
                    listOf(wide, s.rules, s.bridged).forEach(Mat::release)
                }
                Core.bitwise_or(writing, drawn, writing)
                drawn.release()
                // Signs and bullets beside the print stay, whichever way they were taken for writing.
                if (edits.printByLook) signsBesidePrint(rgb, printed).also { Core.subtract(writing, it, writing); it.release() }
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
     * What to remove, from [HandMask]'s chances: strokes it is sure of somewhere, taken whole,
     * down to where it is only a little sure (a stroke fades at its ends and where it thins), and
     * grown by the soft rim a stroke leaves in the photo. Ink it is sure isn't handwriting stays,
     * rim or not: the answer line under the writing, the print it touches. In a [printed] word,
     * only what it is very sure of goes, and not grown: there, a letter the pen ran through
     * matters more than a scrap of the pen.
     */
    private fun handwriting(rgb: Mat, chance: Mat, printed: List<Area>?): Mat {
        val w = chance.cols()
        val h = chance.rows()
        val maybe = Mat()
        Imgproc.threshold(chance, maybe, HAND_MAYBE, 255.0, Imgproc.THRESH_BINARY)
        val labels = Mat()
        val count = Imgproc.connectedComponents(maybe, labels, 8, CvType.CV_32S)
        maybe.release()
        val ids = IntArray(w * h)
        labels.get(0, 0, ids)
        labels.release()
        val chances = ByteArray(w * h)
        chance.get(0, 0, chances)
        val sure = BooleanArray(count)
        for (i in ids.indices) if (ids[i] > 0 && (chances[i].toInt() and 0xFF) > HAND_SURE) sure[ids[i]] = true
        val out = ByteArray(w * h)
        for (i in ids.indices) if (sure[ids[i]] && ids[i] > 0) out[i] = 255.toByte()
        val mask = Mat(h, w, CvType.CV_8UC1)
        mask.put(0, 0, out)
        val rim = max(3, (max(w, h) / HAND_RIM_PER) or 1).toDouble()
        Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(rim, rim)))
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val ink = Mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, max(15, (max(w, h) / 70) or 1), 12.0)
        gray.release()
        val notHand = Mat()
        Imgproc.threshold(chance, notHand, HAND_NOT, 255.0, Imgproc.THRESH_BINARY_INV)
        val handInk = Mat()
        Imgproc.threshold(chance, handInk, HAND_SURE.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_and(handInk, ink, handInk)
        Core.bitwise_and(ink, notHand, notHand)
        Core.subtract(mask, notHand, mask)
        notHand.release()
        if (!printed.isNullOrEmpty()) {
            val words = Mat.zeros(h, w, CvType.CV_8UC1)
            for (a in printed) {
                // Neat handwriting can look printed; the network knows better. A word that's
                // mostly writing to it isn't one to keep letters of.
                val box = Rect(
                    Point((a.left * w).toDouble().coerceIn(0.0, w.toDouble()), (a.top * h).toDouble().coerceIn(0.0, h.toDouble())),
                    Point((a.right * w).toDouble().coerceIn(0.0, w.toDouble()), (a.bottom * h).toDouble().coerceIn(0.0, h.toDouble())),
                )
                if (box.width < 1 || box.height < 1) continue
                val inkIn = ink.submat(box)
                val handIn = handInk.submat(box)
                val inked = Core.countNonZero(inkIn)
                val written = Core.countNonZero(handIn)
                listOf(inkIn, handIn).forEach(Mat::release)
                if (inked == 0 || written > inked * WRITTEN_WORD) continue
                // A little above and below, for accents and the tails of g and y.
                val grow = (a.bottom - a.top) * PRINT_REACH
                Imgproc.rectangle(
                    words,
                    Point(a.left * w.toDouble(), (a.top - grow) * h.toDouble()),
                    Point(a.right * w.toDouble(), (a.bottom + grow) * h.toDouble()),
                    Scalar(255.0),
                    -1,
                )
            }
            val unsure = Mat()
            Imgproc.threshold(chance, unsure, HAND_IN_PRINT, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_and(unsure, words, unsure)
            Core.subtract(mask, unsure, mask)
            listOf(words, unsure).forEach(Mat::release)
        }
        listOf(ink, handInk).forEach(Mat::release)
        return mask
    }

    /**
     * The band just around [mask]: a removed stroke's soft gray edge in the photo, which would
     * otherwise outline the paper put in its place. Ink in it, and a pixel round that, isn't band.
     */
    private fun rimAround(rgb: Mat, mask: Mat): Mat {
        val side = max(rgb.cols(), rgb.rows())
        val reach = max(3, (side / RIM_PER) or 1)
        val band = Mat()
        Imgproc.dilate(mask, band, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(2.0 * reach + 1, 2.0 * reach + 1)))
        Core.subtract(band, mask, band)
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val ink = Mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, max(15, (side / 70) or 1), 12.0)
        gray.release()
        Core.subtract(ink, mask, ink)
        Imgproc.dilate(ink, ink, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        Core.subtract(band, ink, band)
        ink.release()
        return band
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
     * Background left in a page's corners and along its edges, in place: where the crop took in a
     * little desk, a corner is folded or torn off, or the page isn't quite square. That is what
     * differs from the paper, touches the page's edge and stays close to it; a picture printed to
     * the edge reaches further in, and stays. Filled with paper from around it.
     */
    internal fun fillEdges(rgb: Mat) {
        val w = rgb.cols()
        val h = rgb.rows()
        val scale = min(1.0, EDGE_SIDE / max(w, h))
        val small = Mat()
        Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(small, small, Size(3.0, 3.0), 0.0)
        val sw = small.cols()
        val sh = small.rows()
        // The paper: the brighter half of the page's middle.
        val middle = small.submat(sh / 5, sh * 4 / 5, sw / 5, sw * 4 / 5)
        val gray = Mat()
        Imgproc.cvtColor(middle, gray, Imgproc.COLOR_RGB2GRAY)
        val bright = Mat()
        Core.compare(gray, Scalar(medianOf(gray, null).toDouble()), bright, Core.CMP_GE)
        val paper = Core.mean(middle, bright)
        listOf(gray, bright, middle).forEach(Mat::release)
        // Off the paper's color by a clear margin, in brightness or tint.
        val diff = Mat()
        Core.absdiff(small, paper, diff)
        val channels = mutableListOf<Mat>()
        Core.split(diff, channels)
        val off = Mat()
        Core.max(channels[0], channels[1], off)
        Core.max(off, channels[2], off)
        channels.forEach(Mat::release)
        diff.release()
        small.release()
        Imgproc.threshold(off, off, EDGE_STEP, 255.0, Imgproc.THRESH_BINARY)
        val band = max(2.0, min(sw, sh) * EDGE_BAND)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(off, labels, stats, centroids, 8, CvType.CV_32S)
        val keep = BooleanArray(count) { k ->
            if (k == 0) return@BooleanArray false
            val x0 = stats.get(k, Imgproc.CC_STAT_LEFT)[0]
            val y0 = stats.get(k, Imgproc.CC_STAT_TOP)[0]
            val x1 = x0 + stats.get(k, Imgproc.CC_STAT_WIDTH)[0]
            val y1 = y0 + stats.get(k, Imgproc.CC_STAT_HEIGHT)[0]
            val area = stats.get(k, Imgproc.CC_STAT_AREA)[0]
            val touches = x0 <= 0 || y0 <= 0 || x1 >= sw || y1 >= sh
            // Every part of it within the band: along one edge (a strip), or in a corner (both ways).
            val near = (x1 <= band || x0 >= sw - band || y1 <= band || y0 >= sh - band) ||
                ((x1 <= 2 * band || x0 >= sw - 2 * band) && (y1 <= 2 * band || y0 >= sh - 2 * band))
            touches && near && area <= EDGE_MOST * sw * sh
        }
        val labelPx = IntArray(sw * sh).also { labels.get(0, 0, it) }
        off.put(0, 0, ByteArray(sw * sh) { if (keep[labelPx[it]]) -1 else 0 })
        listOf(labels, stats, centroids).forEach(Mat::release)
        if (Core.countNonZero(off) == 0) {
            off.release()
            return
        }
        // Over the soft line between desk and paper too.
        Imgproc.dilate(off, off, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val mask = Mat()
        Imgproc.resize(off, mask, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        off.release()
        Imgproc.threshold(mask, mask, 0.0, 255.0, Imgproc.THRESH_BINARY)
        val filled = paperUnder(rgb, mask)
        filled.copyTo(rgb, mask)
        listOf(filled, mask).forEach(Mat::release)
    }

    /**
     * Takes coffee, tea and water stains out of [rgb], in place. A stain is a translucent tint:
     * yellower and a little darker than this page's paper, smooth, and no brighter or more
     * saturated than a highlighter. It's divided out, so the print under it stays, using the
     * stain's color close by each pixel (from the clear paper near it; a small window follows its
     * rim, a wider one reaches between letters). Found on a smaller copy; corrected at full size
     * only around the stains.
     */
    private fun unstain(rgb: Mat) {
        val side = max(rgb.cols(), rgb.rows())
        val scale = min(1.0, STAIN_SIDE / side)
        val small = Mat()
        Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val w = small.cols()
        val h = small.rows()
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        // The paper: the brighter half of the page, whatever its own color.
        val paperMask = Mat()
        Core.compare(gray, Scalar(medianOf(gray, null).toDouble()), paperMask, Core.CMP_GE)
        val paper = Core.mean(small, paperMask)
        val lab = Mat()
        Imgproc.cvtColor(small, lab, Imgproc.COLOR_RGB2Lab)
        val channels = mutableListOf<Mat>()
        Core.split(lab, channels)
        val (pl, pa, pb) = channels.map { medianOf(it, paperMask).toDouble() }
        channels.forEach(Mat::release)
        val tinted = Mat()
        Core.inRange(lab, Scalar(0.0, pa - 3, pb + 8), Scalar(pl - 5, 255.0, pb + 44), tinted)
        lab.release()
        val hsv = Mat()
        Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGB2HSV)
        val warm = Mat()
        Core.inRange(hsv, Scalar(5.0, 0.0, 0.0), Scalar(25.0, STAIN_SATURATION, 255.0), warm)
        hsv.release()
        Core.bitwise_and(tinted, warm, tinted)
        warm.release()
        // Ink: dark against the paper.
        val ink = Mat()
        Core.compare(gray, Scalar(INK_SHARE * Core.mean(gray, paperMask).`val`[0]), ink, Core.CMP_LT)
        paperMask.release()
        Core.subtract(tinted, ink, tinted)
        // Smooth: a stain has no texture of its own, ink aside.
        val k = (max(9, max(w, h) / 150) or 1).toDouble()
        val clear = Mat()
        Core.bitwise_not(ink, clear)
        val m = Mat()
        clear.convertTo(m, CvType.CV_32F, 1.0 / 255)
        val lum = Mat()
        gray.convertTo(lum, CvType.CV_32F)
        gray.release()
        val sum = Mat()
        val sq = Mat()
        val count = Mat()
        Core.multiply(lum, m, sum)
        Core.multiply(sum, lum, sq)
        Imgproc.boxFilter(sum, sum, -1, Size(k, k), Point(-1.0, -1.0), false)
        Imgproc.boxFilter(sq, sq, -1, Size(k, k), Point(-1.0, -1.0), false)
        Imgproc.boxFilter(m, count, -1, Size(k, k), Point(-1.0, -1.0), false)
        Core.max(count, Scalar(1e-3), count)
        Core.divide(sum, count, sum)
        Core.divide(sq, count, sq)
        Core.multiply(sum, sum, sum)
        Core.subtract(sq, sum, sq)
        val smooth = Mat()
        Core.compare(sq, Scalar(STAIN_TEXTURE * STAIN_TEXTURE), smooth, Core.CMP_LT)
        listOf(m, lum, sum, sq, count, clear).forEach(Mat::release)
        Core.bitwise_and(tinted, smooth, tinted)
        smooth.release()
        // Whole blotches: closed over the text inside them, and big enough to be a stain.
        Imgproc.morphologyEx(tinted, tinted, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        Imgproc.morphologyEx(tinted, tinted, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(2 * k + 1, 2 * k + 1)))
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(tinted, labels, stats, centroids, 8, CvType.CV_32S)
        val least = (max(w, h) * STAIN_SIZE).let { it * it }
        val region = Mat.zeros(h, w, CvType.CV_8UC1)
        val one = Mat()
        for (c in 1 until n) {
            if (stats.get(c, Imgproc.CC_STAT_AREA)[0] < least) continue
            Core.compare(labels, Scalar(c.toDouble()), one, Core.CMP_EQ)
            Core.bitwise_or(region, one, region)
        }
        listOf(labels, stats, centroids, one, tinted, small).forEach(Mat::release)
        if (Core.countNonZero(region) == 0) {
            listOf(region, ink).forEach(Mat::release)
            return
        }
        // Well past its edge: correcting clear paper changes nothing, and the soft rim is stain too.
        val reach = (3 * k.toInt() or 1).toDouble()
        Imgproc.dilate(region, region, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(reach, reach)))

        // At full size, around the stains only.
        val full = Mat()
        Imgproc.resize(region, full, rgb.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
        val fullInk = Mat()
        Imgproc.resize(ink, fullInk, rgb.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
        listOf(region, ink).forEach(Mat::release)
        val kFull = (k / scale).toInt() or 1
        val box = Imgproc.boundingRect(full).let { r ->
            val x0 = max(0, r.x - kFull)
            val y0 = max(0, r.y - kFull)
            Rect(x0, y0, min(rgb.cols(), r.x + r.width + kFull) - x0, min(rgb.rows(), r.y + r.height + kFull) - y0)
        }
        val part = rgb.submat(box)
        val inside = full.submat(box)
        // The clear paper (not ink, nor its soft edge) the stain's color is read from.
        val clearFull = Mat()
        Imgproc.dilate(fullInk.submat(box), clearFull, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        Core.bitwise_not(clearFull, clearFull)
        fullInk.release()
        val weight = Mat()
        clearFull.convertTo(weight, CvType.CV_32F, 1.0 / 255)
        clearFull.release()
        val colour = Mat()
        part.convertTo(colour, CvType.CV_32FC3)
        val weighted = Mat()
        val weight3 = Mat()
        Imgproc.cvtColor(weight, weight3, Imgproc.COLOR_GRAY2RGB)
        Core.multiply(colour, weight3, weighted)
        weight3.release()
        fun tintOver(window: Double): Pair<Mat, Mat> {
            val num = Mat()
            val den = Mat()
            Imgproc.boxFilter(weighted, num, -1, Size(window, window), Point(-1.0, -1.0), false)
            Imgproc.boxFilter(weight, den, -1, Size(window, window), Point(-1.0, -1.0), false)
            val den3 = Mat()
            Core.max(den, Scalar(1e-3), den3)
            Imgproc.cvtColor(den3, den3, Imgproc.COLOR_GRAY2RGB)
            Core.divide(num, den3, num)
            den3.release()
            return num to den
        }
        val (tint, _) = tintOver(kFull.toDouble())
        val (near, nearCount) = tintOver(NEAR_WINDOW)
        val close = Mat()
        Core.compare(nearCount, Scalar(NEAR_CLEAR), close, Core.CMP_GE)
        near.copyTo(tint, close)
        listOf(near, nearCount, close, weighted, weight).forEach(Mat::release)
        // gain = paper / tint, faded in at the region's edge so there's no seam.
        full.release()
        Core.max(tint, Scalar(1.0, 1.0, 1.0), tint)
        val gain = Mat(tint.size(), CvType.CV_32FC3, paper)
        Core.divide(gain, tint, gain)
        tint.release()
        val soft = Mat()
        inside.convertTo(soft, CvType.CV_32F, 1.0 / 255)
        Imgproc.GaussianBlur(soft, soft, Size(0.0, 0.0), kFull / 3.0)
        Imgproc.cvtColor(soft, soft, Imgproc.COLOR_GRAY2RGB)
        Core.subtract(gain, Scalar(1.0, 1.0, 1.0), gain)
        Core.multiply(gain, soft, gain)
        Core.add(gain, Scalar(1.0, 1.0, 1.0), gain)
        Core.multiply(colour, gain, colour)
        colour.convertTo(part, CvType.CV_8UC3)
        listOf(soft, gain, colour, inside, part).forEach(Mat::release)
    }

    /** The middle value of an 8-bit [channel], over [mask] if given. */
    private fun medianOf(channel: Mat, mask: Mat?): Int {
        val hist = Mat()
        Imgproc.calcHist(listOf(channel), MatOfInt(0), mask ?: Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
        val counts = FloatArray(256).also { hist.get(0, 0, it) }
        hist.release()
        val half = counts.sum() / 2
        var seen = 0f
        counts.forEachIndexed { i, c -> seen += c; if (seen >= half) return i }
        return 255
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
        val grayBytes = ByteArray(w * h).also { gray.get(0, 0, it) }
        gray.release()
        // Any color: pen in shadow is too dark to tell by its color, but it's still outside the
        // print. Logos, photos and skin are big shapes, kept below.
        // Straight rules and table lines come out first, so a signature on a signature line takes
        // only itself away.
        val rules = Mat()
        // A little either side of level or upright: on a photo of bent paper, the rules slope.
        val run = (side * 0.06).coerceAtLeast(15.0).toInt() or 1
        val thick = Mat()
        Imgproc.dilate(ink, thick, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val part = Mat()
        rules.create(ink.size(), CvType.CV_8UC1)
        rules.setTo(Scalar(0.0))
        for (upright in listOf(0.0, 90.0)) {
            var degrees = upright - RULE_TILT
            while (degrees <= upright + RULE_TILT) {
                val kernel = PenLines.lineKernel(run, degrees)
                Imgproc.morphologyEx(thick, part, Imgproc.MORPH_OPEN, kernel)
                Core.bitwise_or(rules, part, rules)
                kernel.release()
                degrees += RULE_TILT_STEP
            }
        }
        Core.bitwise_and(rules, ink, rules)
        Imgproc.dilate(rules, rules, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        Core.subtract(ink, rules, ink)
        listOf(rules, part, thick).forEach(Mat::release)

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
        val ids by lazy { IntArray(w * h).also { labels.get(0, 0, it) } }
        for (label in 1 until count) {
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0]
            if (area < minArea) continue
            val bw = stats.get(label, Imgproc.CC_STAT_WIDTH)[0]
            val bh = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0]
            val x = stats.get(label, Imgproc.CC_STAT_LEFT)[0]
            val y = stats.get(label, Imgproc.CC_STAT_TOP)[0]
            if (pictures.any { p -> x >= p[0] && y >= p[1] && x + bw <= p[2] && y + bh <= p[3] }) continue
            // Ruled lines and table borders: long, thin all along, and straight or bowed with the
            // page (so their box can be tall). A line drawn by hand under the print wobbles;
            // joined-up writing is no thin line most of the way.
            if (max(bw, bh) > longLine && area / max(bw, bh) < thin) {
                val shape = lineShape(ids, grayBytes, w, label, x.toInt(), y.toInt(), bw.toInt(), bh.toInt(), heights.getOrNull(heights.size / 2) ?: 0.0)
                if (shape != null && shape.thickness < thin && shape.alone >= RULE_ALONE && !shape.wobbles) continue
                if (shape == null && min(bw, bh) < thin) continue
            }
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
     * Lines drawn by hand under (or through) the print on [rgb]: ink running on nearly level for
     * longer than [UNDERLINE_RUN] letters, which no letter does, and wobbling as a hand does (see
     * [wobbles]); a printed underline is straight and stays. Only the line's own pixels, so the
     * letters it touches keep their shape.
     */
    private fun underlines(rgb: Mat, solid: Mat): Mat {
        val side = max(rgb.cols(), rgb.rows())
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val ink = Mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, max(15, (side / 40) or 1), 18.0)
        val grayBytes = ByteArray(gray.rows() * gray.cols()).also { gray.get(0, 0, it) }
        gray.release()
        // Not along solid shapes: a chart's axis runs on under its bars, and their edges would make it wobble.
        val nearSolid = Mat()
        Imgproc.dilate(solid, nearSolid, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)))
        Core.subtract(ink, nearSolid, ink)
        nearSolid.release()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        var count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
        var st = IntArray(count * 5).also { stats.get(0, 0, it) }
        val heights = (1 until count).filter { st[it * 5 + 4] >= 6 && st[it * 5 + 3] >= 4 }.map { st[it * 5 + 3] }.sorted()
        val lines = Mat.zeros(ink.size(), CvType.CV_8UC1)
        val unit = heights.getOrNull(heights.size / 2)
        if (unit == null) {
            listOf(labels, stats, centroids, ink).forEach(Mat::release)
            return lines
        }
        val length = max(15, (UNDERLINE_RUN * unit).toInt() or 1)
        // A pen line a couple of pixels wide wobbles: thickened a little, it runs straight.
        val thick = Mat()
        Imgproc.dilate(ink, thick, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val part = Mat()
        var degrees = -UNDERLINE_TILT
        while (degrees <= UNDERLINE_TILT) {
            val kernel = PenLines.lineKernel(length, degrees)
            Imgproc.morphologyEx(thick, part, Imgproc.MORPH_OPEN, kernel)
            Core.bitwise_or(lines, part, lines)
            kernel.release()
            degrees += UNDERLINE_STEP
        }
        Core.bitwise_and(lines, ink, lines)
        // Thin strokes only: a chart's bars standing on its axis run level too, and would make it wobble.
        val dist = Mat()
        Imgproc.distanceTransform(ink, dist, Imgproc.DIST_L2, 3)
        val depths = FloatArray(ink.rows() * ink.cols()).also { dist.get(0, 0, it) }
        val half = depths.filter { it > 0f }.sorted().let { it.getOrElse(it.size / 2) { 0f } }
        Imgproc.dilate(dist, dist, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        Imgproc.threshold(dist, part, UNDERLINE_THIN * half, 255.0, Imgproc.THRESH_BINARY)
        part.convertTo(part, CvType.CV_8UC1)
        Core.subtract(lines, part, lines)
        listOf(thick, part, dist).forEach(Mat::release)
        count = Imgproc.connectedComponentsWithStats(lines, labels, stats, centroids, 8, CvType.CV_32S)
        st = IntArray(count * 5).also { stats.get(0, 0, it) }
        val w = ink.cols()
        val ids = IntArray(w * ink.rows()).also { labels.get(0, 0, it) }
        val drawn = BooleanArray(count) { label ->
            label > 0 && st[label * 5 + 2] >= length && st[label * 5 + 2] > st[label * 5 + 3] &&
                lineShape(ids, grayBytes, w, label, st[label * 5], st[label * 5 + 1], st[label * 5 + 2], st[label * 5 + 3], unit.toDouble())
                    // A pen line: a thin stroke, on its own most of the way (a line of bold print
                    // thickened runs level too, but it's letter-high all along), that wobbles.
                    ?.let { it.thickness <= max(UNDERLINE_STROKE_MIN, UNDERLINE_STROKE * unit) && it.alone >= RULE_ALONE && it.wobbles } == true
        }
        lines.put(0, 0, ByteArray(ids.size) { if (drawn[ids[it]]) -1 else 0 })
        listOf(labels, stats, centroids, ink).forEach(Mat::release)
        return lines
    }

    /**
     * Print the reader didn't box, beside what it did: a sign (the + in "1/2 + 1/4 =", an x, a %,
     * a colon) between two printed words on their line and no bigger than a letter, or a list's
     * bullet, a small solid dot or square just before a printed line with nothing printed before
     * it on its row. Alone they don't read as print; an answer written after the last word has
     * print on one side only, and a pen's dot is rarely so neat. Dark and gray only: a sign
     * written in colored pen goes. A mask, grown as removed writing is, to take out of it.
     */
    private fun signsBesidePrint(rgb: Mat, printed: List<Area>): Mat {
        val w = rgb.cols()
        val h = rgb.rows()
        val side = max(w, h)
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val ink = Mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, max(15, (side / 40) or 1), 18.0)
        gray.release()
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val saturation = Mat()
        Core.extractChannel(hsv, saturation, 1)
        hsv.release()
        val boxes = printed.map { doubleArrayOf(it.left * w.toDouble(), it.top * h.toDouble(), it.right * w.toDouble(), it.bottom * h.toDouble()) }
        fun row(cy: Double, size: Double) = boxes.filter { b -> cy > b[1] && cy < b[3] && size <= (b[3] - b[1]) * SIGN_SIZE }
        fun betweenPrint(x: Double, y: Double, bw: Double, bh: Double): Boolean {
            val row = row(y + bh / 2, max(bw, bh))
            val gap = row.map { it[3] - it[1] }.maxOrNull() ?: return false
            return row.any { b -> b[2] <= x && x - b[2] < gap * 1.5 } && row.any { b -> b[0] >= x + bw && b[0] - (x + bw) < gap * 1.5 }
        }
        fun bullet(x: Double, y: Double, bw: Double, bh: Double, area: Double): Boolean {
            if (area < BULLET_SOLID * bw * bh || max(bw, bh) > BULLET_ROUND * min(bw, bh)) return false
            val row = row(y + bh / 2, max(bw, bh))
            val gap = row.map { it[3] - it[1] }.maxOrNull() ?: return false
            return row.any { b -> b[0] >= x + bw && b[0] - (x + bw) < gap * 2.0 } && row.none { b -> b[2] <= x }
        }
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
        val st = IntArray(count * 5).also { stats.get(0, 0, it) }
        val signs = Mat.zeros(h, w, CvType.CV_8UC1)
        val one = Mat()
        for (label in 1 until count) {
            val x = st[label * 5].toDouble()
            val y = st[label * 5 + 1].toDouble()
            val bw = st[label * 5 + 2].toDouble()
            val bh = st[label * 5 + 3].toDouble()
            val area = st[label * 5 + 4].toDouble()
            if (!betweenPrint(x, y, bw, bh) && !bullet(x, y, bw, bh, area)) continue
            Core.compare(labels, Scalar(label.toDouble()), one, Core.CMP_EQ)
            if (Core.mean(saturation, one).`val`[0] > SIGN_NEUTRAL) continue
            Core.bitwise_or(signs, one, signs)
        }
        listOf(ink, saturation, labels, stats, centroids, one).forEach(Mat::release)
        // As far out as removed writing is grown (see [darkHandwriting]).
        val reach = (max(5, side / 220) or 1).toDouble()
        val grow = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(reach, reach))
        Imgproc.dilate(signs, signs, grow, Point(-1.0, -1.0), 2)
        grow.release()
        return signs
    }

    /** How a mark runs, along its length: see [lineShape]. */
    private class LineShape(
        /** Its usual thickness across, in pixels. */
        val thickness: Int,
        /** The share of its length where it runs on its own, no thicker than that. */
        val alone: Double,
        /** Whether it wobbles as a hand does. */
        val wobbles: Boolean,
    )

    /**
     * How mark [label] of [ids] (an image [w] wide, with [gray] its gray levels), in the box at
     * [x], [y], runs; null if it's too short to tell. It wobbles the way a line drawn by hand does
     * when its middle along it, from the gray levels
     * (finer than a pixel, so a printed line's one-pixel steps as it slopes don't count), strays
     * from a parabola fitted a few letters either way ([WOBBLE_REACH] letter heights [unit]) by
     * more than [WOBBLE] pixels on average. A printed rule is straight, or bends gently with the
     * paper, which the parabola follows; the hand trembles. Measured only where the line runs on
     * its own: where letters or writing touch it, it's thicker.
     */
    private fun lineShape(ids: IntArray, gray: ByteArray, w: Int, label: Int, x: Int, y: Int, bw: Int, bh: Int, unit: Double): LineShape? {
        val along = bw >= bh
        val steps = if (along) bw else bh
        val across = if (along) bh else bw
        val h = ids.size / w
        val t = DoubleArray(steps)
        val mid = DoubleArray(steps)
        val extent = IntArray(steps)
        var n = 0
        fun at(i: Int, j: Int) = if (along) (y + j) * w + x + i else (y + i) * w + x + j
        for (i in 0 until steps) {
            var first = -1
            var last = -1
            for (j in 0 until across) {
                if (ids[at(i, j)] == label) {
                    if (first < 0) first = j
                    last = j
                }
            }
            if (first < 0) continue
            // The middle by how dark it is, a pixel past the mark either side.
            var weight = 0.0
            var sum = 0.0
            for (j in first - 1..last + 1) {
                val px = if (along) x + i else x + j
                val py = if (along) y + j else y + i
                if (px !in 0 until w || py !in 0 until h) continue
                val dark = 255.0 - (gray[py * w + px].toInt() and 0xFF)
                weight += dark
                sum += dark * j
            }
            if (weight <= 0.0) continue
            t[n] = i.toDouble()
            mid[n] = sum / weight
            extent[n] = last - first + 1
            n++
        }
        if (n < 3) return null
        val thickness = extent.copyOf(n).sorted()[n / 2]
        val alone = (0 until n).filter { extent[it] <= thickness + 1 }
        val share = alone.size.toDouble() / n
        // Mostly touched by other ink, it can't be told.
        if (share < WOBBLE_ALONE) return LineShape(thickness, share, wobbles = false)
        val reach = WOBBLE_REACH * unit / 2
        fun det(m: Array<DoubleArray>) = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        var squares = 0.0
        var counted = 0
        for (k in alone) {
            // Least squares for mid = a + b·d + c·d² around this step: its value here is a.
            val s = DoubleArray(5)
            val r = DoubleArray(3)
            var near = 0
            for (q in alone) {
                val d = t[q] - t[k]
                if (abs(d) > reach) continue
                near++
                var p = 1.0
                for (e in 0..4) {
                    s[e] += p
                    if (e < 3) r[e] += p * mid[q]
                    p *= d
                }
            }
            if (near < 4) continue
            val a = arrayOf(doubleArrayOf(s[0], s[1], s[2]), doubleArrayOf(s[1], s[2], s[3]), doubleArrayOf(s[2], s[3], s[4]))
            val dd = det(a)
            if (abs(dd) < 1e-12) continue
            val here = det(Array(3) { row -> DoubleArray(3) { e -> if (e == 0) r[row] else a[row][e] } }) / dd
            val e = mid[k] - here
            squares += e * e
            counted++
        }
        return LineShape(thickness, share, wobbles = counted >= 3 && sqrt(squares / counted) > WOBBLE)
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
    /** The band around what's removed reaches about one pixel per this many of the page's long side. */
    private const val RIM_PER = 500
    /** Edges are looked at on a copy this big; off the paper by this many levels in any channel is background. */
    private const val EDGE_SIDE = 600.0
    private const val EDGE_STEP = 45.0
    /** Background reaches at most this share of the page's short side in from its edge (twice that in a corner)... */
    private const val EDGE_BAND = 0.06
    /** ...and covers at most this share of it. */
    private const val EDGE_MOST = 0.06
    /** Stains are looked for on a copy this big on its long side. */
    private const val STAIN_SIDE = 1200.0
    /** No more saturated than this (HSV, 0-255): a highlighter is. */
    private const val STAIN_SATURATION = 110.0
    /** Local spread of brightness (gray levels) below which an area is smooth. */
    private const val STAIN_TEXTURE = 10.0
    /** A stain is at least this share of the page's long side, squared, in area. */
    private const val STAIN_SIZE = 0.01
    /** Darker than this share of the paper is ink. */
    private const val INK_SHARE = 0.55
    /** The small window that follows a stain's rim, and how much clear paper it needs. */
    private const val NEAR_WINDOW = 5.0
    private const val NEAR_CLEAR = 8.0
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

    /** Handwriting: strokes [HandMask] is this sure of (of 255) somewhere, taken down to where it's [HAND_MAYBE] sure. */
    private const val HAND_SURE = 128
    private const val HAND_MAYBE = 38.0
    /** Ink it gives under this chance is surely not handwriting, and is never taken. */
    private const val HAND_NOT = 26.0
    /** ...and grown by about one pixel per this many of the page's long side, for the stroke's soft rim. */
    private const val HAND_RIM_PER = 1000
    /** In a printed word, only what it's this sure of goes... */
    private const val HAND_IN_PRINT = 200.0
    /** ...the word taken this share of its height further up and down; but not a word over this share of whose ink is surely writing. */
    private const val PRINT_REACH = 0.2f
    private const val WRITTEN_WORD = 0.6

    /** A printed sign between words is at most this share of its line's height across... */
    private const val SIGN_SIZE = 0.9
    /** ...and no more saturated than this (0 to 255): a sign in colored pen is writing. */
    private const val SIGN_NEUTRAL = 60.0
    /** A ruled line's middle strays at most this many pixels on average from a parabola over [WOBBLE_REACH] letter heights of it... */
    private const val WOBBLE = 0.35
    private const val WOBBLE_REACH = 12.0
    /** ...measured where it runs alone, which must be at least this share of it. */
    private const val WOBBLE_ALONE = 0.3
    /** A ruled line runs on its own for at least this share of its length (writing on it aside). */
    private const val RULE_ALONE = 0.5
    /** Rules are looked for this many degrees either side of level and upright, in steps of [RULE_TILT_STEP]. */
    private const val RULE_TILT = 10.0
    private const val RULE_TILT_STEP = 2.5
    /** A bullet fills at least this share of its box, and is at most this many times longer one way than the other. */
    private const val BULLET_SOLID = 0.6
    private const val BULLET_ROUND = 1.5
    /** A line drawn under the print runs on, within this many degrees of level, for this many letter heights. */
    private const val UNDERLINE_TILT = 20.0
    private const val UNDERLINE_STEP = 5.0
    private const val UNDERLINE_RUN = 3.0
    /** ...in a stroke at most this many times as deep as the ink's middling depth. */
    private const val UNDERLINE_THIN = 3.0
    /** ...whose stroke is at most this share of a letter's height across, or [UNDERLINE_STROKE_MIN] pixels. */
    private const val UNDERLINE_STROKE = 0.4
    private const val UNDERLINE_STROKE_MIN = 4.0
}
