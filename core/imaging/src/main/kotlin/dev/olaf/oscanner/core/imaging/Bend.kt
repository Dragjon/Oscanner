package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.CurveLine
import dev.olaf.oscanner.core.model.PageCurve

/**
 * The map that flattens a curved page, from a [PageCurve]. Plain math, so it runs in unit tests.
 *
 * Each measured line gets a level height, its value mid-page. On the flat page, the point at
 * height t in column x comes from t + offset(x, t) on the bent page, where the offset is the
 * line's bend at x, blended between the lines above and below t and held beyond the outermost.
 * Coordinates are normalized to the straightened page before it's turned.
 */
object Bend {

    fun lineY(line: CurveLine, x: Float): Float {
        val t = x.coerceIn(line.from, line.to)
        val c = line.coefficients
        return c[0] + t * (c[1] + t * (c[2] + t * c[3]))
    }

    /** Where a line lies once flattened. */
    fun level(line: CurveLine): Float = lineY(line, 0.5f)

    /** How far up or down to look on the bent page for the flat point (x, t). */
    fun offset(curve: PageCurve, x: Float, t: Float): Float {
        val lines = curve.lines
        if (lines.isEmpty()) return 0f
        val first = lines.first()
        if (t <= level(first)) return lineY(first, x) - level(first)
        val last = lines.last()
        if (t >= level(last)) return lineY(last, x) - level(last)
        val below = lines.indexOfFirst { level(it) >= t }
        val a = lines[below - 1]
        val b = lines[below]
        val ta = level(a)
        val tb = level(b)
        val w = if (tb > ta) (t - ta) / (tb - ta) else 0f
        return (1 - w) * (lineY(a, x) - ta) + w * (lineY(b, x) - tb)
    }

    /** Flat page to bent page. */
    fun unflatten(curve: PageCurve, x: Float, y: Float): Float = y + offset(curve, x, y)

    /** Bent page to flat page: the y whose [unflatten] is [y], found by fixed-point steps. */
    fun flatten(curve: PageCurve, x: Float, y: Float): Float {
        var t = y
        repeat(12) { t = y - offset(curve, x, t) }
        return t
    }

    /**
     * Least-squares cubic through (xs, ys), or null when there are too few points or the line
     * wanders too far from any smooth curve (a picture or a smudge, not a text line).
     */
    fun fitLine(xs: FloatArray, ys: FloatArray, maxError: Float): CurveLine? {
        val n = xs.size
        if (n < MIN_POINTS) return null
        // Normal equations for 4 unknowns, solved by Gaussian elimination with pivoting.
        val a = Array(4) { DoubleArray(5) }
        for (i in 0 until n) {
            val p = DoubleArray(4)
            p[0] = 1.0
            for (k in 1..3) p[k] = p[k - 1] * xs[i]
            for (r in 0..3) {
                for (c in 0..3) a[r][c] += p[r] * p[c]
                a[r][4] += p[r] * ys[i]
            }
        }
        for (col in 0..3) {
            val pivot = (col..3).maxBy { kotlin.math.abs(a[it][col]) }
            if (kotlin.math.abs(a[pivot][col]) < 1e-12) return null
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            for (r in 0..3) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (c in col..4) a[r][c] -= f * a[col][c]
            }
        }
        val coefficients = List(4) { (a[it][4] / a[it][it]).toFloat() }
        val line = CurveLine(coefficients, xs.min(), xs.max())
        var squares = 0.0
        for (i in 0 until n) {
            val e = lineY(line, xs[i]) - ys[i]
            squares += e * e
        }
        return line.takeIf { kotlin.math.sqrt(squares / n) <= maxError }
    }

    /**
     * Keeps lines top to bottom, dropping any that would cross the one above anywhere across
     * the page: the map needs lines that stay in order.
     */
    fun ordered(lines: List<CurveLine>): List<CurveLine> {
        val kept = mutableListOf<CurveLine>()
        for (line in lines.sortedBy(::level)) {
            val previous = kept.lastOrNull()
            if (previous == null || SAMPLES.all { lineY(line, it) > lineY(previous, it) + MIN_GAP }) kept += line
        }
        return kept
    }

    private const val MIN_POINTS = 8
    private const val MIN_GAP = 0.004f
    private val SAMPLES = List(11) { it / 10f }
}
