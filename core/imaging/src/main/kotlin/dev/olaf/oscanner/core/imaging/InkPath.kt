package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.LineStyle
import kotlin.math.hypot

/**
 * The line a finger meant: touch points arrive a few pixels apart and a little shaky, and joined
 * straight they look like a polygon. They're steadied (each point eased toward its neighbors),
 * then a Catmull–Rom curve is run through them. The editor and the saved page both draw from
 * this, so a stroke looks the same in both.
 */
object InkPath {

    fun smooth(points: List<Corner>): List<Corner> {
        if (points.size < 3) return points
        // Drop repeats: a still finger reports the same spot many times.
        val distinct = points.fold(mutableListOf<Corner>()) { kept, p ->
            if (kept.isEmpty() || hypot(p.x - kept.last().x, p.y - kept.last().y) > MIN_STEP) kept += p
            kept
        }
        if (distinct.last() != points.last()) distinct += points.last()
        if (distinct.size < 3) return distinct
        // Steady: each inner point a quarter of the way toward its neighbors' middle.
        val steady = distinct.indices.map { i ->
            if (i == 0 || i == distinct.lastIndex) {
                distinct[i]
            } else {
                val a = distinct[i - 1]
                val b = distinct[i + 1]
                val p = distinct[i]
                Corner(p.x * 0.5f + (a.x + b.x) * 0.25f, p.y * 0.5f + (a.y + b.y) * 0.25f)
            }
        }
        val out = ArrayList<Corner>(steady.size * STEPS)
        for (i in 0 until steady.lastIndex) {
            val p0 = steady[(i - 1).coerceAtLeast(0)]
            val p1 = steady[i]
            val p2 = steady[i + 1]
            val p3 = steady[(i + 2).coerceAtMost(steady.lastIndex)]
            for (s in 0 until STEPS) {
                val t = s.toFloat() / STEPS
                out += Corner(catmullRom(p0.x, p1.x, p2.x, p3.x, t), catmullRom(p0.y, p1.y, p2.y, p3.y, t))
            }
        }
        out += steady.last()
        return out
    }

    /** Dash and gap lengths for [style], in multiples of the stroke's width; null for solid. */
    fun pattern(style: LineStyle): FloatArray? = when (style) {
        LineStyle.Solid -> null
        LineStyle.Dashed -> floatArrayOf(3f, 2.2f)
        // A zero-length dash with round caps draws a dot.
        LineStyle.Dotted -> floatArrayOf(0.01f, 1.8f)
    }

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
    }

    /** Points closer than this (a share of the page) are one point. */
    private const val MIN_STEP = 0.0008f
    /** Curve points between each two touch points. */
    private const val STEPS = 6
}
