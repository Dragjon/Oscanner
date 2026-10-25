package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.CurveLine
import dev.olaf.oscanner.core.model.PageCurve

/**
 * The map that flattens a curved page, from a [PageCurve]. Plain math, so it runs in unit tests.
 *
 * Each measured line gets a level height, its value mid-page. On the flat page, the point at
 * height t in column x comes from t + offset(x, t) on the bent page, where the offset is the
 * line's bend at x, blended between the lines above and below t and held beyond the outermost.
 * Coordinates are normalized to the straightened page before it's turned. Across, column u of
 * the flat page comes from column [sourceX] of the straightened one, unsqueezed.
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

    /** The straightened page's column that column [u] of the flat page comes from. */
    fun sourceX(curve: PageCurve, u: Float): Float {
        val c = curve.columns
        if (c.size < 2) return u
        val at = u.coerceIn(0f, 1f) * (c.size - 1)
        val i = at.toInt().coerceAtMost(c.size - 2)
        return c[i] + (at - i) * (c[i + 1] - c[i])
    }

    /** The inverse of [sourceX]: where column [x] of the straightened page lands when flat. */
    fun pageX(curve: PageCurve, x: Float): Float {
        val c = curve.columns
        if (c.size < 2) return x
        val i = (c.indexOfFirst { it > x } - 1).let { if (it < 0) if (x <= c.first()) 0 else c.size - 2 else it }.coerceIn(0, c.size - 2)
        val span = c[i + 1] - c[i]
        val w = if (span > 0) ((x - c[i]) / span).coerceIn(0f, 1f) else 0f
        return (i + w) / (c.size - 1)
    }

    /** Bent page to flat page: the y whose [unflatten] is [y], found by fixed-point steps. */
    fun flatten(curve: PageCurve, x: Float, y: Float): Float {
        var t = y
        repeat(12) { t = y - offset(curve, x, t) }
        return t
    }
}
