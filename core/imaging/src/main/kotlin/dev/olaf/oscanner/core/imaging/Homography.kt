package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad

/**
 * A 3×3 projective map, row-major, with the last entry 1. [squareTo] is the classic closed form
 * for mapping the unit square onto a quad (Heckbert, "Fundamentals of Texture Mapping", 1989).
 */
internal class Homography(private val m: DoubleArray) {

    fun map(x: Double, y: Double): Corner {
        val w = m[6] * x + m[7] * y + m[8]
        return Corner(((m[0] * x + m[1] * y + m[2]) / w).toFloat(), ((m[3] * x + m[4] * y + m[5]) / w).toFloat())
    }

    fun inverse(): Homography {
        val (a, b, c) = Triple(m[0], m[1], m[2])
        val (d, e, f) = Triple(m[3], m[4], m[5])
        val (g, h, i) = Triple(m[6], m[7], m[8])
        val co = doubleArrayOf(
            e * i - f * h, c * h - b * i, b * f - c * e,
            f * g - d * i, a * i - c * g, c * d - a * f,
            d * h - e * g, b * g - a * h, a * e - b * d,
        )
        val det = a * co[0] + b * co[3] + c * co[6]
        return Homography(DoubleArray(9) { co[it] / det })
    }

    companion object {
        /** (0,0), (1,0), (1,1), (0,1) to the quad's top-left, top-right, bottom-right, bottom-left. */
        fun squareTo(quad: Quad): Homography {
            val (x0, y0) = quad.topLeft.let { it.x.toDouble() to it.y.toDouble() }
            val (x1, y1) = quad.topRight.let { it.x.toDouble() to it.y.toDouble() }
            val (x2, y2) = quad.bottomRight.let { it.x.toDouble() to it.y.toDouble() }
            val (x3, y3) = quad.bottomLeft.let { it.x.toDouble() to it.y.toDouble() }
            val sx = x0 - x1 + x2 - x3
            val sy = y0 - y1 + y2 - y3
            if (sx == 0.0 && sy == 0.0) {
                return Homography(doubleArrayOf(x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0, 0.0, 0.0, 1.0))
            }
            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val det = dx1 * dy2 - dx2 * dy1
            val g = (sx * dy2 - dx2 * sy) / det
            val h = (dx1 * sy - sx * dy1) / det
            return Homography(
                doubleArrayOf(
                    x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
                    y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
                    g, h, 1.0,
                ),
            )
        }
    }
}
