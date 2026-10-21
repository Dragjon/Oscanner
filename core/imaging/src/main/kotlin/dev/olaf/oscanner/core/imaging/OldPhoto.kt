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
        val marks = Mat()
        Imgproc.threshold(bright, bright, SPECK_STEP, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.threshold(dark, dark, SPECK_STEP, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_or(bright, dark, marks)

        // Keep marks that are small, or thin bright lines; detail in the picture (hair, leaves,
        // text) comes in big connected patches and stays.
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(marks, labels, stats, centroids, 8, CvType.CV_32S)
        val speck = (side * SPECK_SIZE).let { it * it }
        val long = side * SCRATCH_LENGTH
        val brightPx = ByteArray(bright.total().toInt()).also { bright.get(0, 0, it) }
        val labelPx = IntArray(labels.total().toInt()).also { labels.get(0, 0, it) }
        val brightCount = IntArray(n)
        for (i in labelPx.indices) if (labelPx[i] > 0 && brightPx[i] != 0.toByte()) brightCount[labelPx[i]]++
        val keep = BooleanArray(n) { k ->
            if (k == 0) return@BooleanArray false
            val area = stats.get(k, Imgproc.CC_STAT_AREA)[0]
            val w = stats.get(k, Imgproc.CC_STAT_WIDTH)[0]
            val h = stats.get(k, Imgproc.CC_STAT_HEIGHT)[0]
            val cx = (stats.get(k, Imgproc.CC_STAT_LEFT)[0] + w / 2).toInt()
            val cy = (stats.get(k, Imgproc.CC_STAT_TOP)[0] + h / 2).toInt()
            val busy = busyPx[cy * gray.cols() + cx]
            val smooth = busy < SMOOTH * SMOOTH
            val small = area <= speck && max(w, h) <= side * SPECK_SIDE && smooth
            // A scratch: long, thin (its pixels fill little of its length), bright, and nearly
            // straight; a highlight running round something curved, like a rim, isn't one.
            // Around its middle, too, the picture is fairly plain: a rim's highlight sits on an edge.
            val thin = max(w, h) >= long && area <= max(w, h) * SCRATCH_WIDTH && brightCount[k] >= area * 0.8 &&
                busy < 4 * SMOOTH * SMOOTH && straight(labelPx, gray.cols(), k, stats)
            small || thin
        }
        val mask = Mat(gray.size(), CvType.CV_8UC1)
        mask.put(0, 0, ByteArray(labelPx.size) { if (keep[labelPx[it]]) -1 else 0 })
        listOf(gray, bright, dark, marks, labels, stats, centroids).forEach(Mat::release)
        if (Core.countNonZero(mask) == 0) {
            mask.release()
            return
        }
        // Over the marks' soft edges too.
        Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)))
        Photo.inpaint(rgb, mask, rgb, 3.0, Photo.INPAINT_TELEA)
        mask.release()
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
}
