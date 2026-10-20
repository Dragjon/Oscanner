package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.CurveLine
import dev.olaf.oscanner.core.model.PageCurve
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Measures how text lines bend on a straightened page: dark marks are found with a local
 * threshold, smeared sideways until each line of text is one long blob, and each long, thin
 * blob's middle is traced column by column and fitted with a smooth curve ([Bend.fitLine]).
 */
object CurveDetector {

    /** [page] is RGB, straightened and not yet turned. */
    fun detect(page: Mat): PageCurve {
        val scale = WORK_WIDTH.toDouble() / page.cols()
        val small = Mat()
        Imgproc.resize(page, small, Size(WORK_WIDTH.toDouble(), (page.rows() * scale).coerceAtLeast(1.0)), 0.0, 0.0, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        small.release()
        val w = gray.cols()
        val h = gray.rows()

        val ink = Mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, odd(w / 30), 15.0)
        gray.release()
        // Letters into words into lines: a wide, one-pixel-tall smear joins neighbors on a line
        // without reaching the next line down.
        val smear = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(3, w / 40).toDouble(), 1.0))
        Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_CLOSE, smear)
        smear.release()
        val speck = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 2.0))
        Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_OPEN, speck)
        speck.release()

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
        ink.release()
        centroids.release()
        val ids = IntArray(w * h)
        labels.get(0, 0, ids)
        labels.release()

        val lines = mutableListOf<CurveLine>()
        val stat = IntArray(5)
        for (id in 1 until count) {
            stats.get(id, 0, stat)
            val (left, top, width, height) = listOf(stat[0], stat[1], stat[2], stat[3])
            if (width < w * MIN_LINE_WIDTH || height > h * MAX_LINE_HEIGHT || width < height * MIN_ASPECT) continue
            val step = max(1, width / SAMPLES_PER_LINE)
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            var x = left
            while (x < left + width) {
                var sum = 0L
                var n = 0
                for (y in top until top + height) {
                    if (ids[y * w + x] == id) {
                        sum += y
                        n++
                    }
                }
                if (n > 0) {
                    xs += x.toFloat() / w
                    ys += (sum.toFloat() / n) / h
                }
                x += step
            }
            Bend.fitLine(xs.toFloatArray(), ys.toFloatArray(), MAX_FIT_ERROR)?.let(lines::add)
        }
        stats.release()
        val ordered = Bend.ordered(lines)
        return PageCurve(if (ordered.size >= MIN_LINES) ordered else emptyList())
    }

    /**
     * Rows of [Bend.unflatten] for [page] in bands, so the full-size lookup map never has to be
     * held at once. Returns a new Mat the same size and type.
     */
    fun flatten(page: Mat, curve: PageCurve): Mat {
        val w = page.cols()
        val h = page.rows()
        val out = Mat(h, w, page.type())
        val lines = curve.lines
        val levels = FloatArray(lines.size) { Bend.level(lines[it]) }
        // Each line's bend in every column, in pixels: offsets[i][x].
        val offsets = Array(lines.size) { i -> FloatArray(w) { x -> (Bend.lineY(lines[i], (x + 0.5f) / w) - levels[i]) * h } }
        val band = 128
        val mapX = Mat()
        val mapY = Mat()
        var y0 = 0
        while (y0 < h) {
            val rows = minOf(band, h - y0)
            val xs = FloatArray(rows * w)
            val ys = FloatArray(rows * w)
            for (r in 0 until rows) {
                val y = y0 + r
                val t = (y + 0.5f) / h
                // Which two measured lines this row sits between, and how far along.
                var i = levels.indexOfFirst { it >= t }
                val (a, b, wt) = when {
                    i == 0 -> Triple(0, 0, 0f)
                    i < 0 -> Triple(levels.size - 1, levels.size - 1, 0f)
                    else -> Triple(i - 1, i, (t - levels[i - 1]) / (levels[i] - levels[i - 1]))
                }
                val oa = offsets[a]
                val ob = offsets[b]
                val base = r * w
                for (x in 0 until w) {
                    xs[base + x] = x.toFloat()
                    ys[base + x] = y + (1 - wt) * oa[x] + wt * ob[x]
                }
            }
            mapX.create(rows, w, CvType.CV_32FC1)
            mapY.create(rows, w, CvType.CV_32FC1)
            mapX.put(0, 0, xs)
            mapY.put(0, 0, ys)
            val target = out.rowRange(y0, y0 + rows)
            Imgproc.remap(page, target, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            target.release()
            y0 += rows
        }
        mapX.release()
        mapY.release()
        return out
    }

    private fun odd(n: Int) = (max(3, n) / 2) * 2 + 1

    private const val WORK_WIDTH = 1200
    private const val MIN_LINE_WIDTH = 0.2
    private const val MAX_LINE_HEIGHT = 0.05
    private const val MIN_ASPECT = 8
    private const val SAMPLES_PER_LINE = 40
    private const val MAX_FIT_ERROR = 0.004f
    private const val MIN_LINES = 3
}
