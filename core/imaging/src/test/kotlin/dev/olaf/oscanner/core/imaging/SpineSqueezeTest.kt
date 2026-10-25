package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.PageCurve
import dev.olaf.oscanner.core.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A book's page photographed from above: the paper rises off the table and turns down steeply
 * into the spine, so its lines bend and, near the spine, its letters crowd together.
 */
class SpineSqueezeTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = nu.pattern.OpenCV.loadLocally()

        /** The flat page. */
        private const val W = 1500
        private const val H = 2000
        private val WORDS = "The quick brown fox jumps over a lazy dog while giant typography keeps your page quite plain".split(' ')
    }

    /**
     * Paper standing [rise] page widths off the table at most, down to it at both sides and
     * steepest into the spine, on the right or, when [spineLeft], the left.
     */
    private class Book(val rise: Double, val spineLeft: Boolean, val cameraX: Double, val cameraY: Double, val distance: Double) {
        private val n = 6000
        /** For each step along the flat page: where it is across, under the camera's view, and how high. */
        private val across = DoubleArray(n + 1)
        private val seen = DoubleArray(n + 1)
        private val magnify = DoubleArray(n + 1)

        init {
            var x = 0.0
            for (i in 0..n) {
                val s = i.toDouble() / n
                if (i > 0) {
                    val slope = (height(s) - height((i - 1.0) / n)) * n
                    x += sqrt(1 - slope * slope) / n
                }
                across[i] = x * W
            }
            for (i in 0..n) {
                val z = height(i.toDouble() / n) * W
                magnify[i] = distance * W / (distance * W - z)
            }
            val cx = cameraX * across[n]
            for (i in 0..n) seen[i] = cx + (across[i] - cx) * magnify[i]
        }

        fun height(s: Double): Double {
            val u = if (spineLeft) 1 - s else s
            return rise * sin(PI * u / 2) * (1 - u.pow(8))
        }

        /** How wide the straightened photo of the page is: its sides lie on the table. */
        val width get() = Math.round(across[n]).toInt()

        /** [flat], as the straightened photo shows it. */
        fun photograph(flat: Mat): Mat {
            val w = width
            val xs = FloatArray(w * H)
            val ys = FloatArray(w * H)
            var i = 0
            for (px in 0 until w) {
                while (i < n - 1 && seen[i + 1] < px + 0.5) i++
                val f = ((px + 0.5 - seen[i]) / (seen[i + 1] - seen[i])).coerceIn(0.0, 1.0)
                val s = (i + f) / n
                val g = magnify[i] + f * (magnify[i + 1] - magnify[i])
                val cy = cameraY * H
                for (py in 0 until H) {
                    xs[py * w + px] = (s * W - 0.5).toFloat()
                    ys[py * w + px] = (cy + (py + 0.5 - cy) / g - 0.5).toFloat()
                }
            }
            val mapX = Mat(H, w, CvType.CV_32FC1).apply { put(0, 0, xs) }
            val mapY = Mat(H, w, CvType.CV_32FC1).apply { put(0, 0, ys) }
            return Mat().also { Imgproc.remap(flat, it, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE) }
        }

        val view get() = Viewpoint(cameraX, cameraY, distance * W / width)
    }

    private fun text(): Mat {
        val page = Mat(H, W, CvType.CV_8UC3, Scalar(248.0, 248.0, 245.0))
        var y = 120
        var n = 0
        while (y < H - 100) {
            var x = 100
            var k = n * 7
            val end = if (n % 6 == 5) 750 else 1400
            while (true) {
                val word = WORDS[k++ % WORDS.size]
                val width = Imgproc.getTextSize(word, Imgproc.FONT_HERSHEY_SIMPLEX, 1.1, 2, IntArray(1)).width.toInt()
                if (x + width > end) break
                Imgproc.putText(page, word, Point(x.toDouble(), y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 1.1, Scalar(25.0, 25.0, 25.0), 2)
                x += width + 18
            }
            n++
            y += 52
        }
        return page
    }

    /** A grid: level lines every 100 px from 150, upright ones every 100 px from 100. */
    private fun grid(): Mat {
        val m = Mat(H, W, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        for (y in 150 until H - 100 step 100) Imgproc.line(m, Point(0.0, y.toDouble()), Point(W.toDouble(), y.toDouble()), Scalar(0.0, 0.0, 0.0), 1)
        // Two pixels wide, so they stay dark however they're resampled.
        for (x in 100 until W step 100) Imgproc.rectangle(m, Point(x - 1.0, 0.0), Point(x.toDouble(), H.toDouble()), Scalar(0.0, 0.0, 0.0), -1)
        return m
    }

    /**
     * How far, at worst in flat-page pixels, the grid's upright lines are from where they are on
     * the flat page, in rows between the level lines; [m] is scaled back to the flat page's width.
     */
    private fun columnsOff(m: Mat): Double {
        val gray = Mat()
        Imgproc.cvtColor(m, gray, Imgproc.COLOR_RGB2GRAY)
        val w = gray.cols()
        var worst = 0.0
        for (row in listOf(300, 1000, 1700)) {
            val px = ByteArray(w)
            gray.get(row, 0, px)
            val found = mutableListOf<Double>()
            var x = 0
            while (x < w) {
                if ((px[x].toInt() and 255) < 160) {
                    var mass = 0.0
                    var at = 0.0
                    while (x < w && (px[x].toInt() and 255) < 220) {
                        val v = 255.0 - (px[x].toInt() and 255)
                        mass += v
                        at += v * x
                        x++
                    }
                    found += (at / mass + 0.5) * W / w - 0.5
                }
                x++
            }
            // Squeezed so far that two lines run together: off by the gap between them at least.
            if (found.size != 14) return 100.0
            found.forEachIndexed { i, f -> worst = maxOf(worst, abs(f - 100.0 * (i + 1))) }
        }
        return worst
    }

    /** The worst rise and fall of [m]'s level lines across the page, in pixels. */
    private fun rowsOff(m: Mat): Double {
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
            // Between two upright lines.
            val mid = w / 2 + 25
            val first = centre(mid, start.toDouble()) ?: continue
            val ys = mutableListOf(first)
            for (side in listOf(mid + 1 until w - w / 15, (mid - 1) downTo w / 15)) {
                var c = first
                for (x in side) c = centre(x, c)?.also { ys += it } ?: break
            }
            worst = maxOf(worst, ys.max() - ys.min())
        }
        return worst
    }

    private class Result(val columns: Double, val rows: Double, val width: Double, val columnsUnfixed: Double)

    private fun flatten(book: Book): Result {
        val curve = CurveDetector.detect(book.photograph(text()), book.view)
        val bent = book.photograph(grid())
        val flat = CurveDetector.flatten(bent, curve)
        val linesOnly = CurveDetector.flatten(bent, PageCurve(curve.lines))
        return Result(columnsOff(flat), rowsOff(flat), flat.cols().toDouble() / W, columnsOff(linesOnly))
    }

    @Test
    fun aPageCurvingIntoTheSpineIsUnsqueezed() {
        listOf(
            Book(0.1, spineLeft = false, cameraX = 0.5, cameraY = 0.5, distance = 1.3),
            Book(0.1, spineLeft = true, cameraX = 0.5, cameraY = 0.45, distance = 1.3),
            // Both pages in one photo: the camera over the spine.
            Book(0.1, spineLeft = false, cameraX = 1.0, cameraY = 0.5, distance = 1.7),
            Book(0.06, spineLeft = true, cameraX = 0.0, cameraY = 0.55, distance = 1.7),
        ).map { book ->
            val r = flatten(book)
            val label = "rise ${book.rise}, spine ${if (book.spineLeft) "left" else "right"}, camera at ${book.cameraX}"
            label to r
        }.forEach { (label, r) ->
            assertTrue("$label: columns ${r.columns} px off", r.columns < 8)
            assertTrue("$label: unsqueezing helps", r.columns < r.columnsUnfixed / 3)
            assertTrue("$label: rows ${r.rows} px off", r.rows < 8)
            assertEquals("$label: width", 1.0, r.width, 0.01)
        }
    }

    @Test
    fun aRoughGuessOfTheCameraStillHelps() {
        // When the photo doesn't say its lens, or the crop is off, the camera is guessed.
        val book = Book(0.1, spineLeft = false, cameraX = 0.5, cameraY = 0.5, distance = 1.3)
        val photo = book.photograph(text())
        val bent = book.photograph(grid())
        val v = book.view
        val unfixed = columnsOff(CurveDetector.flatten(bent, PageCurve(CurveDetector.detect(photo, v).lines)))
        for (k in listOf(0.75, 1.33)) for (dx in listOf(0.0, 0.2)) {
            val off = columnsOff(CurveDetector.flatten(bent, CurveDetector.detect(photo, Viewpoint(v.x + dx, v.y, v.distance * k))))
            assertTrue("camera ${k}x as far, ${dx} across: columns $off px off, $unfixed unfixed", off < unfixed / 2)
        }
    }

    @Test
    fun theCameraIsFoundFromTheCorners() {
        // A camera 1.2 page widths up, over (0.4, 0.6), tilted 15° and 8°, at 26 mm equivalent.
        val pw = 1000.0
        val ph = 1400.0
        val photoW = 3000
        val photoH = 4000
        val f = 26 / 43.27 * sqrt(photoW.toDouble() * photoW + photoH.toDouble() * photoH)
        val centre = doubleArrayOf(0.4 * pw, 0.6 * ph, -1.2 * pw)
        val a = 15 * PI / 180
        val b = 8 * PI / 180
        // Looking down +z at the page; rows of R are the camera's axes in page coordinates.
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(a), -sin(a)), doubleArrayOf(0.0, sin(a), cos(a)))
        val ry = arrayOf(doubleArrayOf(cos(b), 0.0, sin(b)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(b), 0.0, cos(b)))
        val r = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> rx[i][k] * ry[k][j] } } }
        fun project(x: Double, y: Double): Corner {
            val d = doubleArrayOf(x - centre[0], y - centre[1], -centre[2])
            val c = DoubleArray(3) { i -> (0..2).sumOf { r[i][it] * d[it] } }
            return Corner(((photoW / 2 + f * c[0] / c[2]) / photoW).toFloat(), ((photoH / 2 + f * c[1] / c[2]) / photoH).toFloat())
        }
        val quad = Quad.of(listOf(project(0.0, 0.0), project(pw, 0.0), project(pw, ph), project(0.0, ph)))
        val view = Viewpoint.fromQuad(quad, photoW, photoH, pw, ph, 26.0)!!
        assertEquals(0.4, view.x, 0.02)
        assertEquals(0.6, view.y, 0.02)
        assertEquals(1.2, view.distance, 0.03)
    }
}
