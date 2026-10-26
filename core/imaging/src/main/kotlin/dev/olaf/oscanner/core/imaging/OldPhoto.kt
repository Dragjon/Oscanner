package dev.olaf.oscanner.core.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min

/**
 * The Photo look, for photographs and old prints especially. Fading lifts the darks, flattens
 * contrast and tints everything one way (yellow, magenta); each color channel is stretched back
 * between its own darkest and lightest, which undoes all three, and the color that fading
 * drained is given back a little. Dust and the scratches on a print are small, isolated specks
 * and thin bright lines that stand out from what's around them; they're filled in from it.
 * Where a corner or edge is torn off, [mendTorn] fills the gap with more of the picture.
 */
internal object OldPhoto {

    fun restore(rgb: Mat): Mat {
        val out = rgb.clone()
        clean(out)
        levels(out)
        saturate(out, SATURATION)
        return out
    }

    /** Each channel stretched so [CLIP] of it sits at either end; a channel already full is left. */
    private fun levels(rgb: Mat) {
        val channels = mutableListOf<Mat>()
        Core.split(rgb, channels)
        for (c in channels) {
            val (low, high) = ends(c)
            if (high - low < MIN_RANGE) continue
            val gain = 255.0 / (high - low)
            c.convertTo(c, -1, gain, -low * gain)
        }
        Core.merge(channels, rgb)
        channels.forEach(Mat::release)
    }

    /** The values below which, and above which, [CLIP] of an 8-bit [channel] falls. */
    private fun ends(channel: Mat): Pair<Double, Double> {
        val hist = Mat()
        Imgproc.calcHist(listOf(channel), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
        val counts = FloatArray(256).also { hist.get(0, 0, it) }
        hist.release()
        val total = counts.sum()
        var seen = 0f
        var low = 0
        while (low < 255 && seen + counts[low] < total * CLIP) seen += counts[low++]
        seen = 0f
        var high = 255
        while (high > 0 && seen + counts[high] < total * CLIP) seen += counts[high--]
        return low.toDouble() to high.toDouble()
    }

    private fun saturate(rgb: Mat, amount: Double) {
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val channels = mutableListOf<Mat>()
        Core.split(hsv, channels)
        channels[1].convertTo(channels[1], -1, amount, 0.0)
        Core.merge(channels, hsv)
        Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
        hsv.release()
        channels.forEach(Mat::release)
    }

    /** Dust specks, and thin bright scratches, filled in from around them, in place. */
    internal fun clean(rgb: Mat) {
        val side = max(rgb.cols(), rgb.rows())
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        // What's around each pixel, with anything a few pixels across smoothed away.
        val window = (max(5, side / 300) * 2 + 1)
        val around = Mat()
        Imgproc.medianBlur(gray, around, min(window, MAX_MEDIAN))
        val bright = Mat()
        val dark = Mat()
        Core.subtract(gray, around, bright)
        Core.subtract(around, gray, dark)
        // How busy the picture is around each pixel, the marks themselves smoothed away: dust
        // lands anywhere, but on a smooth patch (sky, skin, a wall) it's surely dust, while a
        // small bright or dark point among edges (a catchlight in an eye) is the picture's own.
        val f = Mat()
        around.convertTo(f, CvType.CV_32F)
        val sq = Mat()
        Core.multiply(f, f, sq)
        val reach = Size(window * 3.0, window * 3.0)
        Imgproc.blur(f, f, reach)
        Imgproc.blur(sq, sq, reach)
        Core.multiply(f, f, f)
        Core.subtract(sq, f, sq)
        val busyPx = FloatArray(sq.total().toInt()).also { sq.get(0, 0, it) }
        listOf(f, sq, around).forEach(Mat::release)
        // Marks that stand out plainly, then fainter ones: a faded print's paler sky is as pale
        // as the paper, so its dust stands out by only 20 or so levels, and stretching the colors
        // back makes that plain.
        val mask = marks(bright, dark, busyPx, side, SPECK_STEP, faint = false)
        val faint = marks(bright, dark, busyPx, side, FAINT_STEP, faint = true)
        Core.bitwise_or(mask, faint, mask)
        listOf(gray, bright, dark, faint).forEach(Mat::release)
        if (Core.countNonZero(mask) == 0) {
            mask.release()
            return
        }
        // Over the marks' soft edges too.
        Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)))
        Photo.inpaint(rgb, mask, rgb, 3.0, Photo.INPAINT_TELEA)
        mask.release()
    }

    /**
     * A print's torn-off corners and edges, in place: where the desk it lay on, colored [desk],
     * shows inside the crop, at the edge, along with the white paper core a tear leaves. Each
     * gap has a sharp edge against the picture; a pale sky reaching the edge, as pale as the
     * desk, fades into the picture instead, and stays. Filled from the rest of the picture.
     * [desk] is the colors seen around the crop: one, or several for a checked cloth or wood.
     */
    internal fun mendTorn(rgb: Mat, desk: List<Scalar>) {
        val mask = tornMask(rgb, desk) ?: return
        PatchFill.fill(rgb, mask)
        mask.release()
    }

    /** Where [rgb] is torn away, at its full size; null when nothing is. */
    internal fun tornMask(rgb: Mat, desk: List<Scalar>): Mat? {
        val palette = deskPalette(desk)
        if (palette.isEmpty()) return null
        val patterned = palette.size > 1
        val scale = min(1.0, TORN_SIDE / max(rgb.cols(), rgb.rows()))
        val small = Mat()
        Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(small, small, Size(3.0, 3.0), 0.0)
        val w = small.cols()
        val h = small.rows()
        // Near one of the desk's colors, by how different colors look (Lab).
        val labPx = toLab(small)
        // Which of the desk's colors each pixel is, or -1.
        val which = IntArray(w * h) { i ->
            palette.indexOfFirst { d ->
                val dl = labPx[i * 3] - d[0]
                val da = labPx[i * 3 + 1] - d[1]
                val db = labPx[i * 3 + 2] - d[2]
                dl * dl + da * da + db * db < TORN_NEAR * TORN_NEAR
            }
        }
        val near = Mat(h, w, CvType.CV_8UC1)
        near.put(0, 0, ByteArray(w * h) { if (which[it] >= 0) -1 else 0 })
        if (patterned) {
            // A desk color the picture has plenty of too (a cream check, and a pale sky) is only
            // desk among the desk's other colors: as far from them as the pattern is coarse.
            val shared = sharedColors(which, w, h, palette.size)
            if (shared.any { it } && !shared.all { it }) {
                val reach = patternReach(which, w, h, shared)
                val own = Mat(h, w, CvType.CV_8UC1)
                own.put(0, 0, ByteArray(w * h) { val c = which[it]; if (c >= 0 && !shared[c]) -1 else 0 })
                val side = 2.0 * reach + 3
                Imgproc.dilate(own, own, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(side, side)))
                Core.bitwise_and(near, own, near)
                own.release()
            }
            // A pattern's in-between colors (where checks meet, the grain's soft lines) are desk too.
            Imgproc.morphologyEx(near, near, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)))
        }
        // The paper core a tear leaves: pale and colorless.
        val hsv = Mat()
        Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGB2HSV)
        val white = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, TORN_WHITE), Scalar(180.0, 60.0, 255.0), white)
        hsv.release()
        val whitePx = ByteArray(w * h).also { white.get(0, 0, it) }
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        val gx = Mat()
        val gy = Mat()
        Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0)
        Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1)
        Core.magnitude(gx, gy, gx)
        val edgePx = FloatArray(w * h).also { gx.get(0, 0, it) }
        listOf(gx, gy, gray).forEach(Mat::release)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(near, labels, stats, centroids, 8, CvType.CV_32S)
        val labelPx = IntArray(w * h).also { labels.get(0, 0, it) }
        listOf(labels, centroids, near).forEach(Mat::release)
        // How sharp each gap's edge is inside the picture: the mean gradient along it, away
        // from the photo's own edge.
        // And how much of it runs along the white core, on it or just past it.
        val edgeSum = DoubleArray(n)
        val edgeCount = IntArray(n)
        val edgeWhite = IntArray(n)
        val zero: Byte = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val p = y * w + x
            val k = labelPx[p]
            if (k == 0) continue
            val beside = intArrayOf(p - 1, p + 1, p - w, p + w).filter { labelPx[it] != k }
            if (beside.isNotEmpty()) {
                edgeSum[k] += edgePx[p].toDouble()
                edgeCount[k]++
                if (whitePx[p] != zero || beside.any { whitePx[it] != zero }) edgeWhite[k]++
            }
        }
        val keep = BooleanArray(n) { k ->
            if (k == 0 || edgeCount[k] == 0) return@BooleanArray false
            val x0 = stats.get(k, Imgproc.CC_STAT_LEFT)[0]
            val y0 = stats.get(k, Imgproc.CC_STAT_TOP)[0]
            val x1 = x0 + stats.get(k, Imgproc.CC_STAT_WIDTH)[0]
            val y1 = y0 + stats.get(k, Imgproc.CC_STAT_HEIGHT)[0]
            val area = stats.get(k, Imgproc.CC_STAT_AREA)[0]
            val touches = x0 <= 0 || y0 <= 0 || x1 >= w || y1 >= h
            // With several desk colors, some may well be in the picture too: a gap must show
            // the tear's white core along its edge as well.
            val cored = !patterned || edgeWhite[k] >= TORN_CORED * edgeCount[k]
            touches && area >= TORN_LEAST * w * h && area <= TORN_MOST * w * h && edgeSum[k] / edgeCount[k] >= TORN_EDGE && cored
        }
        stats.release()
        val mask = Mat(h, w, CvType.CV_8UC1)
        mask.put(0, 0, ByteArray(w * h) { if (keep[labelPx[it]]) -1 else 0 })
        if (Core.countNonZero(mask) == 0) {
            listOf(mask, small, white).forEach(Mat::release)
            return null
        }
        // The white core along the tear, joined to the gap.
        small.release()
        Core.bitwise_or(white, mask, white)
        val step = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        repeat(max(2, (max(w, h) * TORN_RIM).toInt())) {
            Imgproc.dilate(mask, mask, step)
            Core.bitwise_and(mask, white, mask)
        }
        white.release()
        // And the soft line where it meets the picture.
        Imgproc.dilate(mask, mask, step, org.opencv.core.Point(-1.0, -1.0), 2)
        val full = Mat()
        Imgproc.resize(mask, full, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Imgproc.threshold(full, full, 0.0, 255.0, Imgproc.THRESH_BINARY)
        mask.release()
        return full
    }

    /** [rgb]'s pixels in Lab, three floats each. */
    private fun toLab(rgb: Mat): FloatArray {
        val lab = Mat()
        rgb.convertTo(lab, CvType.CV_32FC3, 1.0 / 255)
        Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2Lab)
        val px = FloatArray(lab.total().toInt() * 3).also { lab.get(0, 0, it) }
        lab.release()
        return px
    }

    /** For each of [colors] desk colors, whether the middle of the picture has plenty of it too. */
    private fun sharedColors(which: IntArray, w: Int, h: Int, colors: Int): BooleanArray {
        val counts = IntArray(colors)
        var total = 0
        for (y in h / 10 until h * 9 / 10) for (x in w / 10 until w * 9 / 10) {
            total++
            val c = which[y * w + x]
            if (c >= 0) counts[c]++
        }
        return BooleanArray(colors) { counts[it] > TORN_SHARED * total }
    }

    /**
     * How coarse the desk's pattern is, in pixels: along the photo's edge, through the gap, the
     * middle length of a stretch of [shared] colors between two of the desk's own.
     */
    private fun patternReach(which: IntArray, w: Int, h: Int, shared: BooleanArray): Int {
        val rim = (0 until w).map { it } + (1 until h).map { it * w + w - 1 } +
            (w - 2 downTo 0).map { (h - 1) * w + it } + (h - 2 downTo 1).map { it * w }
        val runs = ArrayList<Int>()
        var since = -1
        var sharedSeen = 0
        rim.forEachIndexed { i, p ->
            val c = which[p]
            when {
                c >= 0 && !shared[c] -> {
                    // Soft lines between checks are neither color: a run is mostly shared ones.
                    if (since >= 0 && i - since > 1 && sharedSeen * 2 >= i - since - 1) runs += i - since - 1
                    since = i
                    sharedSeen = 0
                }
                c >= 0 -> sharedSeen++
            }
        }
        if (runs.isEmpty()) return 2
        return min(runs.sorted()[runs.size / 2] / 2 + 2, max(w, h) / 10)
    }

    /**
     * The few colors a desk is made of, in Lab, from [samples] of it: each sample joins the first
     * color it is near, and a color only a few samples have (a crumb, a pen lying there) is left out.
     */
    internal fun deskPalette(samples: List<Scalar>): List<FloatArray> {
        if (samples.isEmpty()) return emptyList()
        val rgb = Mat(1, samples.size, CvType.CV_8UC3)
        samples.forEachIndexed { i, s -> rgb.put(0, i, s.`val`[0], s.`val`[1], s.`val`[2]) }
        val px = toLab(rgb)
        rgb.release()
        val sums = ArrayList<DoubleArray>()
        val counts = ArrayList<Int>()
        for (i in samples.indices) {
            val k = sums.indices.firstOrNull { k ->
                val c = sums[k]
                val n = counts[k]
                val dl = px[i * 3] - c[0] / n
                val da = px[i * 3 + 1] - c[1] / n
                val db = px[i * 3 + 2] - c[2] / n
                dl * dl + da * da + db * db < TORN_NEAR * TORN_NEAR
            }
            if (k == null) {
                sums += doubleArrayOf(px[i * 3].toDouble(), px[i * 3 + 1].toDouble(), px[i * 3 + 2].toDouble())
                counts += 1
            } else {
                for (c in 0..2) sums[k][c] += px[i * 3 + c].toDouble()
                counts[k] = counts[k] + 1
            }
        }
        return sums.indices
            .filter { counts[it] >= TORN_COMMON * samples.size }
            .sortedByDescending { counts[it] }
            .take(TORN_COLORS)
            .map { k -> FloatArray(3) { c -> (sums[k][c] / counts[k]).toFloat() } }
    }

    /**
     * Of the pixels standing [step] levels out of [bright] or [dark], those that are dust or a
     * scratch. Detail in the picture (hair, leaves, text) comes in big connected patches and stays.
     * A [faint] mark counts only on plain ground with nothing else standing out close by: a
     * printed caption's letters, ripples on water and a weathervane's arms are faint marks too,
     * but in company.
     */
    private fun marks(bright: Mat, dark: Mat, busyPx: FloatArray, side: Int, step: Double, faint: Boolean): Mat {
        val b = Mat()
        val d = Mat()
        val marks = Mat()
        Imgproc.threshold(bright, b, step, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.threshold(dark, d, step, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_or(b, d, marks)
        d.release()
        val cols = marks.cols()
        val rows = marks.rows()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(marks, labels, stats, centroids, 8, CvType.CV_32S)
        val brightPx = ByteArray(b.total().toInt()).also { b.get(0, 0, it) }
        val labelPx = IntArray(labels.total().toInt()).also { labels.get(0, 0, it) }
        val brightCount = IntArray(n)
        for (i in labelPx.indices) if (labelPx[i] > 0 && brightPx[i] != 0.toByte()) brightCount[labelPx[i]]++
        // How many marked pixels lie in any box, for telling a lone mark from one in company.
        val sums = if (faint) {
            val ones = Mat()
            Imgproc.threshold(marks, ones, 0.0, 1.0, Imgproc.THRESH_BINARY)
            val sum = Mat()
            Imgproc.integral(ones, sum, CvType.CV_32S)
            ones.release()
            IntArray(sum.total().toInt()).also { sum.get(0, 0, it); sum.release() }
        } else null
        val speck = (side * SPECK_SIZE).let { it * it }
        val long = side * SCRATCH_LENGTH
        val keep = BooleanArray(n) { k ->
            if (k == 0) return@BooleanArray false
            val area = stats.get(k, Imgproc.CC_STAT_AREA)[0]
            val x0 = stats.get(k, Imgproc.CC_STAT_LEFT)[0].toInt()
            val y0 = stats.get(k, Imgproc.CC_STAT_TOP)[0].toInt()
            val w = stats.get(k, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val h = stats.get(k, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            val length = max(w, h)
            val busy = busyPx[(y0 + h / 2) * cols + x0 + w / 2]
            if (sums != null) {
                if (busy >= PLAIN * PLAIN) return@BooleanArray false
                val r = ALONE
                val left = max(0, x0 - r)
                val top = max(0, y0 - r)
                val right = min(cols, x0 + w + r)
                val bottom = min(rows, y0 + h + r)
                val s = cols + 1
                val inBox = sums[bottom * s + right] - sums[top * s + right] - sums[bottom * s + left] + sums[top * s + left]
                if (inBox - area > area / 2 + 2) return@BooleanArray false
            }
            val smooth = busy < SMOOTH * SMOOTH
            val small = area <= speck && length <= side * SPECK_SIDE && smooth
            // A scratch: long, thin (its pixels fill little of its length), bright, and nearly
            // straight; a highlight running round something curved, like a rim, isn't one.
            // Around its middle, too, the picture is fairly plain: a rim's highlight sits on an edge.
            // Alone on plain ground, a short one a pixel or two wide is one too.
            val reach = length >= long || sums != null && length >= side * SHORT_SCRATCH && area <= length * 2.5
            val thin = reach && area <= length * SCRATCH_WIDTH && brightCount[k] >= area * 0.8 &&
                busy < 4 * SMOOTH * SMOOTH && straight(labelPx, cols, k, stats)
            small || thin
        }
        val mask = Mat(marks.size(), CvType.CV_8UC1)
        mask.put(0, 0, ByteArray(labelPx.size) { if (keep[labelPx[it]]) -1 else 0 })
        listOf(b, marks, labels, stats, centroids).forEach(Mat::release)
        return mask
    }

    /** Whether component [k] of [labels] fits in a rotated rectangle a few pixels wide. */
    private fun straight(labels: IntArray, cols: Int, k: Int, stats: Mat): Boolean {
        val x0 = stats.get(k, Imgproc.CC_STAT_LEFT)[0].toInt()
        val y0 = stats.get(k, Imgproc.CC_STAT_TOP)[0].toInt()
        val w = stats.get(k, Imgproc.CC_STAT_WIDTH)[0].toInt()
        val h = stats.get(k, Imgproc.CC_STAT_HEIGHT)[0].toInt()
        val points = ArrayList<org.opencv.core.Point>()
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
            if (labels[y * cols + x] == k) points += org.opencv.core.Point(x.toDouble(), y.toDouble())
        }
        val box = Imgproc.minAreaRect(org.opencv.core.MatOfPoint2f(*points.toTypedArray()))
        return min(box.size.width, box.size.height) <= SCRATCH_BEND
    }

    /** The share of each channel clipped at either end when stretching. */
    private const val CLIP = 0.005f
    /** A channel spanning less than this (8-bit levels) is flat: left alone, not blown up. */
    private const val MIN_RANGE = 24.0
    private const val SATURATION = 1.15
    /** A mark stands this many levels above or below what's around it. */
    private const val SPECK_STEP = 40.0
    /** Or this many, where the picture around is [PLAIN]. */
    private const val FAINT_STEP = 16.0
    /** Plainer than [SMOOTH]: sky, a wall (gray levels, standard deviation). */
    private const val PLAIN = 5.0
    /** On plain ground, a scratch this share of the long side long is one. */
    private const val SHORT_SCRATCH = 0.015
    /** A faint mark is alone with nothing else standing out within this many pixels. */
    private const val ALONE = 12
    private const val MAX_MEDIAN = 15
    /** Around a speck the picture varies by less than this (gray levels, standard deviation). */
    private const val SMOOTH = 12.0
    /** Dust: at most this share of the photo's long side across, squared in area, and on any side. */
    private const val SPECK_SIZE = 0.004
    private const val SPECK_SIDE = 0.01
    /** A scratch is at least this share of the long side long, and about this many pixels wide. */
    private const val SCRATCH_LENGTH = 0.04
    private const val SCRATCH_WIDTH = 4.0
    /** A straight scratch stays inside a band this many pixels wide along its length. */
    private const val SCRATCH_BEND = 8.0
    /** Tears are looked for on a copy this big. */
    private const val TORN_SIDE = 640.0
    /** As near the desk's color as this (Lab distance) is desk. */
    private const val TORN_NEAR = 14f
    /** A desk has at most this many colors, each seen in at least this share of the samples. */
    private const val TORN_COLORS = 4
    private const val TORN_COMMON = 0.12
    /** A desk color covering more than this share of the middle of the picture is the picture's too. */
    private const val TORN_SHARED = 0.03
    /** On a desk of several colors, at least this share of a gap's edge runs along the tear's white core. */
    private const val TORN_CORED = 0.25
    /** A gap is between these shares of the photo. */
    private const val TORN_LEAST = 0.0005
    private const val TORN_MOST = 0.25
    /** A gap's edge against the picture is at least this sharp (mean Sobel gradient). */
    private const val TORN_EDGE = 60.0
    /** The paper core along a tear: at least this bright, and at most this share of the long side wide. */
    private const val TORN_WHITE = 190.0
    private const val TORN_RIM = 0.02
}
