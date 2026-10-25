package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.PageCurve
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class CurveDetectorTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = nu.pattern.OpenCV.loadLocally()

        private const val W = 1500
        private const val H = 2000
        private val WORDS = "The quick brown fox jumps over a lazy dog while giant typography keeps your page quite plain".split(' ')
    }

    /** A page of text in [columns], with a six-row table above it when [table]. */
    private fun page(table: Boolean, columns: List<Pair<Int, Int>> = listOf(100 to 1400)): Mat {
        val page = Mat(H, W, CvType.CV_8UC3, Scalar(248.0, 248.0, 245.0))
        if (table) repeat(6) { i -> Imgproc.line(page, Point(100.0, 160.0 + i * 90), Point(1400.0, 160.0 + i * 90), Scalar(30.0, 30.0, 30.0), 3) }
        var y = if (table) 700 else 120
        var n = 0
        while (y < H - 100) {
            for ((x0, x1) in columns) {
                var x = x0
                var k = n * 7
                // Every sixth line ends a paragraph halfway.
                val end = if (n % 6 == 5) (x0 + x1) / 2 else x1
                while (true) {
                    val word = WORDS[k++ % WORDS.size]
                    val width = Imgproc.getTextSize(word, Imgproc.FONT_HERSHEY_SIMPLEX, 1.1, 2, IntArray(1)).width.toInt()
                    if (x + width > end) break
                    Imgproc.putText(page, word, Point(x.toDouble(), y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 1.1, Scalar(25.0, 25.0, 25.0), 2)
                    x += width + 18
                }
            }
            n++
            y += 52
        }
        return page
    }

    /** Thin level lines every 100 px, to see what a bend and its flattening do. */
    private fun probe(): Mat {
        val m = Mat(H, W, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        for (y in 150 until H - 100 step 100) Imgproc.line(m, Point(0.0, y.toDouble()), Point(W.toDouble(), y.toDouble()), Scalar(0.0, 0.0, 0.0), 1)
        return m
    }

    /** Like a book's page: lines dip toward the spine on the right, more toward the bottom. */
    private fun bend(page: Mat, amount: Double): Mat {
        val xs = FloatArray(W * H)
        val ys = FloatArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val u = x.toDouble() / W
            val t = y.toDouble() / H
            xs[y * W + x] = x.toFloat()
            ys[y * W + x] = (y - amount * H * u * u * u * (0.4 + 1.2 * t) + amount * H * 0.3 * (u - 0.5) * (t - 0.5)).toFloat()
        }
        val mapX = Mat(H, W, CvType.CV_32FC1).apply { put(0, 0, xs) }
        val mapY = Mat(H, W, CvType.CV_32FC1).apply { put(0, 0, ys) }
        return Mat().also { Imgproc.remap(page, it, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE) }
    }

    /** The worst rise and fall of [m]'s probe lines across the page, in pixels. */
    private fun crooked(m: Mat): Double {
        val gray = Mat()
        Imgproc.cvtColor(m, gray, Imgproc.COLOR_RGB2GRAY)
        val w = gray.cols()
        val px = ByteArray(w * H)
        gray.get(0, 0, px)
        fun centre(x: Int, around: Double): Double? {
            var mass = 0.0
            var at = 0.0
            for (y in (around - 12).toInt()..(around + 12).toInt()) {
                if (y !in 0 until H) continue
                val v = 255 - (px[y * w + x].toInt() and 255)
                if (v > 30) {
                    mass += v
                    at += v * y
                }
            }
            return if (mass > 0) at / mass else null
        }
        var worst = 0.0
        for (start in 150 until H - 100 step 100) {
            val mid = centre(w / 2, start.toDouble()) ?: continue
            val ys = mutableListOf(mid)
            for (side in listOf(w / 2 + 1 until w - 100, (w / 2 - 1) downTo 100)) {
                var c = mid
                for (x in side) c = centre(x, c)?.also { ys += it } ?: break
            }
            worst = maxOf(worst, ys.max() - ys.min())
        }
        return worst
    }

    /** How crooked the probe is once bent by [amount] and flattened by what [page] shows of the bend. */
    private fun leftOver(page: Mat, amount: Double): Double {
        val curve = CurveDetector.detect(bend(page, amount))
        val bent = bend(probe(), amount)
        // The lines only: this bend isn't one paper makes, so it says nothing true of the squeeze
        // (see SpineSqueezeTest).
        return crooked(if (curve.lines.isEmpty()) bent else CurveDetector.flatten(bent, PageCurve(curve.lines)))
    }

    @Test
    fun flatPagesAreLeftAlone() {
        for (table in listOf(false, true)) assertTrue(CurveDetector.detect(page(table)).lines.isEmpty())
    }

    @Test
    fun textLinesStraightenABentPage() {
        // Bent, the lines rise and fall by 46 and 81 px.
        assertTrue(leftOver(page(table = false), 0.02) < 5)
        assertTrue(leftOver(page(table = false), 0.04) < 8)
        assertTrue(leftOver(page(table = false, columns = listOf(100 to 700, 800 to 1400)), 0.04) < 10)
    }

    @Test
    fun textBelowATableStraightensTheRestOfThePage() {
        // The table's rules alone show too little bend, at the top, to straighten the text below.
        assertTrue(leftOver(page(table = true), 0.02) < 4)
        assertTrue(leftOver(page(table = true), 0.04) < 6)
    }
}
