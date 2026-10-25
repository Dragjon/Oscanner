package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.CurveLine
import dev.olaf.oscanner.core.model.PageCurve
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
 *
 * The bend also shows how the paper stands off the table, and so how it's squeezed from side to
 * side where it turns away toward a spine: paper nearer the camera looks bigger about the point
 * under it, so lines spread apart from that point where the page rises and close up where it
 * sinks. How fast the spread changes across the page is the paper's slope, and the slope how
 * much wider each column is once laid flat.
 */
object CurveDetector {

    /** [page] is RGB, straightened and not yet turned; [view] is where the camera was over it. */
    fun detect(page: Mat, view: Viewpoint? = null): PageCurve {
        val aspect = page.rows().toDouble() / page.cols()
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
        val squeeze = bend.unsqueeze(view ?: Viewpoint.above(aspect))
        return bend.asLines().copy(columns = squeeze?.first.orEmpty(), widen = squeeze?.second ?: 1f)
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

    /**
     * The fitted bend: [a] by term, measured between levels [t0] and [t1], across [x0]…[x1], on
     * the [lines] it was fitted to.
     */
    private class Surface(val a: DoubleArray, val t0: Float, val t1: Float, val x0: Float, val x1: Float, val lines: List<Traced>) {

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
         * How much lines spread apart per unit of height, dd/dt, in each of [STRIPS] strips from
         * [x0] to [x1]: a straight fit of where the lines are in the strip against their levels.
         * Measured strip by strip rather than read off the surface, which is too smooth to
         * follow paper dropping sharply into a spine. Null when too few lines cross most strips.
         */
        fun spreads(): DoubleArray? {
            // Each line's own height, set by the surface so its samples line up with it.
            val heights = lines.map { line -> line.xs.indices.sumOf { line.ys[it] - d(line.xs[it].toDouble(), line.level.toDouble()) } / line.xs.size }
            val measured = DoubleArray(STRIPS) { Double.NaN }
            for (b in 0 until STRIPS) {
                val from = x0 + (x1 - x0) * b / STRIPS
                val to = x0 + (x1 - x0) * (b + 1) / STRIPS
                val ts = ArrayList<Double>()
                val vs = ArrayList<Double>()
                lines.forEachIndexed { l, line ->
                    var sum = 0.0
                    var n = 0
                    for (i in line.xs.indices) if (line.xs[i] in from..to) {
                        sum += line.ys[i] - heights[l]
                        n++
                    }
                    if (n > 0) {
                        ts += line.level.toDouble()
                        vs += sum / n
                    }
                }
                if (ts.size < MIN_STRIP_LINES) continue
                val mt = ts.average()
                val mv = vs.average()
                val stt = ts.sumOf { (it - mt) * (it - mt) }
                if (stt <= 0) continue
                measured[b] = ts.indices.sumOf { (ts[it] - mt) * (vs[it] - mv) } / stt
            }
            if (measured.count { !it.isNaN() } < STRIPS * 2 / 3) return null
            // Gaps take their neighbors' values, then each strip the middle of it and its two.
            val known = measured.indices.filter { !measured[it].isNaN() }
            val filled = DoubleArray(STRIPS) { b ->
                if (!measured[b].isNaN()) return@DoubleArray measured[b]
                val before = known.lastOrNull { it < b }
                val after = known.firstOrNull { it > b }
                when {
                    before == null -> measured[after!!]
                    after == null -> measured[before]
                    else -> measured[before] + (measured[after] - measured[before]) * (b - before) / (after - before)
                }
            }
            return DoubleArray(STRIPS) { b -> listOf(filled[(b - 1).coerceAtLeast(0)], filled[b], filled[(b + 1).coerceAtMost(STRIPS - 1)]).sorted()[1] }
        }

        /**
         * Where the flat page's columns come from, and how much wider it is, as seen from [view];
         * null when the bend fits no paper seen from there. Paper at height z looks bigger by
         * g = D / (D − z), so lines spread by g − 1 per unit of height. Lines only show the
         * spread up to a level of their own, and only where they reach; the page's sides, where
         * the crop's corners are, lie on the table. So the paper's slope at each end of the lines
         * is followed down to the side, and the lower of the two sides is the table; beyond the
         * lines the paper comes down to it in a straight slope, since the steepest part, into a
         * spine, is often where the text has already ended.
         */
        fun unsqueeze(view: Viewpoint): Pair<List<Float>, Float>? {
            val measured = spreads() ?: return null
            fun spread(x: Double): Double {
                val at = ((x - x0) / (x1 - x0) * STRIPS - 0.5).coerceIn(0.0, STRIPS - 1.0)
                val b = at.toInt().coerceAtMost(STRIPS - 2)
                return measured[b] + (at - b) * (measured[b + 1] - measured[b])
            }
            val n = 400
            val first = spread(x0.toDouble())
            val last = spread(x1.toDouble())
            val reach = (x1 - x0) / STRIPS * 3.0
            val left = min(first, first - (spread(x0 + reach) - first) / reach * x0)
            val right = min(last, last + (last - spread(x1 - reach)) / reach * (1 - x1))
            val table = min(left, right)
            val across = DoubleArray(n + 1)
            val heights = DoubleArray(n + 1)
            for (i in 0..n) {
                val x = i.toDouble() / n
                val s = when {
                    x < x0 -> table + (first - table) * x / x0
                    x > x1 -> table + (last - table) * (1 - x) / (1 - x1)
                    else -> spread(x)
                }
                // Spread is g over the lines' own g, less 1: the ratio is the paper's to the table's.
                val g = (1 + s) / (1 + table)
                if (g !in 0.6..1.6) return null
                // In page widths: where the paper is under the camera's view of it, and how high.
                heights[i] = view.distance * (1 - 1 / g)
                across[i] = view.x + (x - view.x) / g
            }
            val along = DoubleArray(n + 1)
            for (i in 1..n) {
                val dx = across[i] - across[i - 1]
                if (dx <= 0) return null
                along[i] = along[i - 1] + min(hypot(dx, heights[i] - heights[i - 1]), dx * MAX_STRETCH)
            }
            val widen = (along[n] / (across[n] - across[0])).toFloat()
            if (widen < MIN_WIDEN) return null
            var i = 0
            val columns = List(COLUMNS) { k ->
                val s = along[n] * k / (COLUMNS - 1)
                while (i < n - 1 && along[i + 1] < s) i++
                val step = along[i + 1] - along[i]
                ((i + if (step > 0) ((s - along[i]) / step).coerceIn(0.0, 1.0) else 0.0) / n).toFloat()
            }
            return columns to widen
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
        return Surface(a, keep.minOf { it.level }, keep.maxOf { it.level }, starts[2], ends[2], keep)
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
     * held at once. Returns a new Mat of the same type, [widen] times as wide: the page's own
     * widening, or 1 to keep its size (a chosen paper size), unsqueezing within it.
     */
    fun flatten(page: Mat, curve: PageCurve, widen: Float = curve.widen): Mat {
        val sw = page.cols()
        val h = page.rows()
        val w = max(1, Math.round(sw * widen))
        val out = Mat(h, w, page.type())
        val lines = curve.lines
        val levels = FloatArray(lines.size) { Bend.level(lines[it]) }
        // The straightened page's column for each flat one, normalized, and each line's bend
        // there, in pixels: offsets[i][x].
        val from = FloatArray(w) { x -> Bend.sourceX(curve, (x + 0.5f) / w) }
        val offsets = Array(lines.size) { i -> FloatArray(w) { x -> (Bend.lineY(lines[i], from[x]) - levels[i]) * h } }
        val columns = FloatArray(w) { x -> from[x] * sw - 0.5f }
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
                    xs[base + x] = columns[x]
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
    /** How many strips across the page the lines' spread is measured in. */
    private const val STRIPS = 40
    /** A strip crossed by fewer lines than this can't tell their spread. */
    private const val MIN_STRIP_LINES = 6
    /** Paper steeper than this (about 70°) is taken as this steep: steeper can't be read. */
    private const val MAX_STRETCH = 3.0
    /** Less widening than this is no squeeze worth undoing. */
    private const val MIN_WIDEN = 1.002f
    /** How many columns the squeeze is handed to [flatten] as. */
    private const val COLUMNS = 65
    /** How many level lines the bend is handed to [flatten] as. */
    private const val LINES = 12
}
