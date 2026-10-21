package dev.olaf.oscanner.core.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Screens and projected slides come in a few shapes. A photo taken at an angle measures them a
 * little off, so a measured shape close to one of them is taken to be it.
 */
object SlideShape {
    /** Long side ÷ short side: 16:9, 16:10 and 4:3. */
    val SHAPES = listOf(16f / 9f, 16f / 10f, 4f / 3f)

    /** How far off, as a share, a measured shape may be and still count as a standard one. */
    private const val TOLERANCE = 0.05f

    /** The standard shape nearest to a [width] × [height] outline, or null if none is close. */
    fun snap(width: Int, height: Int): Float? {
        if (width <= 0 || height <= 0) return null
        val measured = max(width, height).toFloat() / min(width, height)
        return SHAPES.minBy { abs(it - measured) }.takeIf { abs(it - measured) / it <= TOLERANCE }
    }
}
