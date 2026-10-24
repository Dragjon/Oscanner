package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.CurveLine
import dev.olaf.oscanner.core.model.PageCurve
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Measures how a page bends: one smooth bend for the whole page, fitted to its ruled lines and
 * the lines of text beyond them (or, without rules, its lines of text alone), and none at all
 * when the page is already flat.
 *
 * Fitting each line on its own and blending between them followed every wobble of handwriting
 * and every slanted line, and bent flat pages. One surface, d(x, t) = Σ a_jk·(x − ½)^j·t^k for
 * j = 1…3 and k = 0…2, can only bend the way paper does, and lines it can't explain are dropped.
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
        // Rules and table edges are drawn straight, so they show the bend best; handwriting
        // wanders and slants. Where the rules end, as below a table, the text takes over.
        val rules = ruleLines(gray)
        val traced = if (rules.size >= MIN_RULES) rules + textBeyond(rules, textLines(gray)) else textLines(gray)
        gray.release()
        val bend = fit(traced) ?: return PageCurve(emptyList())
        if (bend.size() < MIN_BEND) return PageCurve(emptyList())
        return bend.asLines()
    }

    private class Traced(val xs: FloatArray, val ys: FloatArray) {
        val level = ys.average().toFloat()
    }

    /** Long, thin, nearly level marks, even when bent: rules, table edges, underlines. */
    private fun ruleLines(gray: Mat): List<Traced> {
        val w = gray.cols()
        val ink = Mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, odd(w / 30), 10.0)
        // Short level pieces survive a bend; a small reach up and down joins them where it steps.
        val piece = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size((w / 25).toDouble(), 1.0))
        Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_OPEN, piece)
        piece.release()
        val join = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size((w / 60).toDouble(), 3.0))
        Imgproc.dilate(ink, ink, join)
        join.release()
        return trace(ink, minWidth = 0.3, maxHeight = 0.04, minAspect = 0, samples = 60, minPoints = 12)
    }

    /**
     * The lines of [text] above or below where [rules] are: a table or a form's ruled part
     * shows the bend only where it is, and the text around it shows the rest.
     */
    private fun textBeyond(rules: List<Traced>, text: List<Traced>): List<Traced> {
        val top = rules.minOf { it.level }
        val bottom = rules.maxOf { it.level }
        return text.filter { it.level < top - RULE_MARGIN || it.level > bottom + RULE_MARGIN }
    }

    /** Lines of text: letters smeared sideways into words, words into lines. */
    private fun textLines(gray: Mat): List<Traced> {
        val w = gray.cols()
        val ink = Mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, odd(w / 30), 15.0)
        // One pixel tall, so it joins neighbors on a line without reaching the next line down.
        val smear = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(3, w / 20).toDouble(), 1.0))
        Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_CLOSE, smear)
        smear.release()
        val speck = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 2.0))
        Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_OPEN, speck)
        speck.release()
        return trace(ink, minWidth = 0.2, maxHeight = 0.05, minAspect = 8, samples = 40, minPoints = 8)
    }

    /**
     * The middle of each long, flat blob in [ink], column by column. Columns where something
     * thick (a finger, a picture) has run into it are skipped. Releases [ink].
     */
    private fun trace(ink: Mat, minWidth: Double, maxHeight: Double, minAspect: Int, samples: Int, minPoints: Int): List<Traced> {
        val w = ink.cols()
        val h = ink.rows()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
        ink.release()
        centroids.release()
        val ids = IntArray(w * h)
        labels.get(0, 0, ids)
        labels.release()
        val out = mutableListOf<Traced>()
        val stat = IntArray(5)
        for (id in 1 until count) {
            stats.get(id, 0, stat)
            val left = stat[0]
            val top = stat[1]
            val width = stat[2]
            val height = stat[3]
            if (width < w * minWidth) continue
            val columns = (left until left + width step max(1, width / samples)).toList()
            val thickness = IntArray(columns.size)
            val middles = FloatArray(columns.size)
            columns.forEachIndexed { i, x ->
                var sum = 0L
                var n = 0
                for (y in top until top + height) {
                    if (ids[y * w + x] == id) {
                        sum += y
                        n++
                    }
                }
                thickness[i] = n
                middles[i] = if (n > 0) sum.toFloat() / n else 0f
            }
            val typical = thickness.filter { it > 0 }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
            // How thick it is, not how tall its box is: a strongly bent line has a tall box.
            if (typical > h * maxHeight || width < typical * minAspect) continue
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            columns.forEachIndexed { i, x ->
                val n = thickness[i]
                if (n > 0 && n <= THICK * typical + 1) {
                    xs += x.toFloat() / w
                    ys += middles[i] / h
                }
            }
            if (xs.size >= minPoints) out += Traced(xs.toFloatArray(), ys.toFloatArray())
        }
        stats.release()
        return out
    }

    /** The fitted bend: [a] by term, measured between levels [t0] and [t1], across [x0]…[x1]. */
    private class Surface(val a: DoubleArray, val t0: Float, val t1: Float, val x0: Float, val x1: Float) {

        /** Up or down from its level, at x, on the line that sits at level t. */
        fun d(x: Double, t: Double): Double {
            val at = terms(x - 0.5, t)
            return at.indices.sumOf { a[it] * at[it] }
        }

        /** How far it strays from straight, at its worst: a tilt alone isn't a bend. */
        fun size(): Float {
            var worst = 0.0
            for (r in 0..10) {
                val t = t0 + (t1 - t0) * r / 10.0
                val xs = DoubleArray(21) { x0 + (x1 - x0) * it / 20.0 }
                val ds = DoubleArray(21) { d(xs[it], t) }
                val mx = xs.average()
                val md = ds.average()
                var sxy = 0.0
                var sxx = 0.0
                for (i in xs.indices) {
                    sxy += (xs[i] - mx) * (ds[i] - md)
                    sxx += (xs[i] - mx) * (xs[i] - mx)
                }
                val slope = if (sxx > 0) sxy / sxx else 0.0
                for (i in xs.indices) worst = max(worst, abs(ds[i] - md - slope * (xs[i] - mx)))
            }
            return worst.toFloat()
        }

        /**
         * The bend as level lines, top to bottom, for [flatten]: each the cubic t + d(x, t), held
         * level beyond where lines were measured.
         */
        fun asLines(): PageCurve = PageCurve(
            List(LINES) { i ->
                val t = t0 + (t1 - t0) * i / (LINES - 1.0)
                val b = DoubleArray(3) { j -> (0..2).sumOf { k -> a[j * 3 + k] * t.pow(k) } }
                // Σ b_j·(x − ½)^j, written out in powers of x.
                val c0 = t - 0.5 * b[0] + 0.25 * b[1] - 0.125 * b[2]
                val c1 = b[0] - b[1] + 0.75 * b[2]
                val c2 = b[1] - 1.5 * b[2]
                val c3 = b[2]
                CurveLine(listOf(c0.toFloat(), c1.toFloat(), c2.toFloat(), c3.toFloat()), x0, x1)
            },
        )
    }

    /** u^j·t^k for j = 1…3, k = 0…2, in that order. */
    private fun terms(u: Double, t: Double) = DoubleArray(TERMS) { p -> u.pow(p / 3 + 1) * t.pow(p % 3) }

    /**
     * Least squares over every line at once, each line free to sit at its own height and each
     * counting the same however long it is. Lines that stray from the fit are dropped and it's
     * fitted again; null when fewer than three lines are left.
     */
    private fun fit(lines: List<Traced>): Surface? {
        var keep = lines
        var a = DoubleArray(TERMS)
        for (round in 0 until ROUNDS) {
            if (keep.size < MIN_LINES) return null
            val ata = Array(TERMS) { DoubleArray(TERMS) }
            val atb = DoubleArray(TERMS)
            val rowsByLine = keep.map(::centeredTerms)
            keep.forEachIndexed { l, line ->
                val rows = rowsByLine[l]
                val weight = 1.0 / line.xs.size
                for (i in line.xs.indices) {
                    val r = rows[i]
                    val y = (line.ys[i] - line.level).toDouble()
                    for (p in 0 until TERMS) {
                        atb[p] += weight * r[p] * y
                        for (q in 0 until TERMS) ata[p][q] += weight * r[p] * r[q]
                    }
                }
            }
            for (p in 0 until TERMS) ata[p][p] += SMOOTH
            a = solve(ata, atb) ?: return null
            val misses = keep.mapIndexed { l, line ->
                val rows = rowsByLine[l]
                var squares = 0.0
                for (i in line.xs.indices) {
                    val e = (line.ys[i] - line.level) - (0 until TERMS).sumOf { rows[i][it] * a[it] }
                    squares += e * e
                }
                sqrt(squares / line.xs.size)
            }
            val limit = max(MIN_MISS, MISS_FACTOR * misses.sorted()[misses.size / 2])
            val kept = keep.filterIndexed { i, _ -> misses[i] <= limit }
            if (kept.size == keep.size) break
            keep = kept
        }
        if (keep.size < MIN_LINES) return null
        // Only as far across as at least three lines reach: one line alone can't vouch for a bend.
        val starts = keep.map { it.xs.min() }.sorted()
        val ends = keep.map { it.xs.max() }.sortedDescending()
        return Surface(a, keep.minOf { it.level }, keep.maxOf { it.level }, starts[2], ends[2])
    }

    /** Each sample's terms less the line's average, so the line's own height cancels out. */
    private fun centeredTerms(line: Traced): Array<DoubleArray> {
        val rows = Array(line.xs.size) { i -> terms(line.xs[i] - 0.5, line.level.toDouble()) }
        val mean = DoubleArray(TERMS) { p -> rows.sumOf { it[p] } / rows.size }
        for (r in rows) for (p in 0 until TERMS) r[p] -= mean[p]
        return rows
    }

    /** Gaussian elimination with pivoting; null when the system is singular. */
    private fun solve(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
        val n = v.size
        val a = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) m[r][c] else v[r] } }
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { abs(a[it][col]) }
            if (abs(a[pivot][col]) < 1e-18) return null
            val tmp = a[col]
            a[col] = a[pivot]
            a[pivot] = tmp
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (c in col..n) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
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
                val i = levels.indexOfFirst { it >= t }
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
    private const val TERMS = 9
    private const val ROUNDS = 5
    private const val MIN_LINES = 3
    private const val MIN_RULES = 4
    /** Text this close to the ruled part, as a share of page height, is in it or written on it. */
    private const val RULE_MARGIN = 0.03f
    /** A sample this many times thicker than the line usually is belongs to something else on it. */
    private const val THICK = 2.5
    /** A little ridge, so the bend is no bigger than the lines ask for. */
    private const val SMOOTH = 1e-4
    /** Lines missing the fit by more than this, and this many times the typical miss, are dropped. */
    private const val MIN_MISS = 0.0012
    private const val MISS_FACTOR = 2.5
    /** Less bend than this, as a share of page height, is a flat page: left as it is. */
    private const val MIN_BEND = 0.004f
    /** How many level lines the bend is handed to [flatten] as. */
    private const val LINES = 12
}
