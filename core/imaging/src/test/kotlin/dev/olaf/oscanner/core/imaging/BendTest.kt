package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.CurveLine
import dev.olaf.oscanner.core.model.PageCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BendTest {

    /** A line that sags by [sag] at its right end, like text running into a book's spine. */
    private fun sagging(level: Float, sag: Float) = CurveLine(listOf(level - sag * 0.125f, 0f, 0f, sag), 0f, 1f)

    @Test
    fun fitRecoversACubic() {
        val xs = FloatArray(20) { 0.1f + it * 0.04f }
        val ys = FloatArray(20) { 0.3f + 0.02f * xs[it] - 0.05f * xs[it] * xs[it] + 0.08f * xs[it] * xs[it] * xs[it] }
        val line = Bend.fitLine(xs, ys, maxError = 0.001f)
        assertNotNull(line)
        line!!
        assertEquals(0.3f, line.coefficients[0], 1e-3f)
        assertEquals(0.08f, line.coefficients[3], 5e-3f)
    }

    @Test
    fun fitRejectsScatter() {
        val xs = FloatArray(20) { it / 20f }
        val ys = FloatArray(20) { if (it % 2 == 0) 0.2f else 0.4f }
        assertNull(Bend.fitLine(xs, ys, maxError = 0.01f))
    }

    @Test
    fun measuredLinesBecomeLevel() {
        val curve = PageCurve(listOf(sagging(0.3f, 0.04f), sagging(0.6f, 0.06f)))
        for (line in curve.lines) {
            val level = Bend.level(line)
            for (x in listOf(0f, 0.5f, 0.9f)) {
                // The flat page at the line's level, in any column, shows the bent line.
                assertEquals(Bend.lineY(line, x), Bend.unflatten(curve, x, level), 1e-5f)
            }
        }
    }

    @Test
    fun flattenUndoesUnflatten() {
        val curve = PageCurve(listOf(sagging(0.2f, 0.03f), sagging(0.5f, 0.05f), sagging(0.8f, 0.07f)))
        for (y in listOf(0.05f, 0.35f, 0.65f, 0.95f)) {
            val bent = Bend.unflatten(curve, 0.8f, y)
            assertEquals(y, Bend.flatten(curve, 0.8f, bent), 1e-4f)
        }
    }

    @Test
    fun crossingLinesAreDropped() {
        val a = CurveLine(listOf(0.40f, 0f, 0f, 0f), 0f, 1f)
        val crossing = CurveLine(listOf(0.38f, 0.05f, 0f, 0f), 0f, 1f)
        val b = CurveLine(listOf(0.50f, 0f, 0f, 0f), 0f, 1f)
        assertEquals(listOf(a, b), Bend.ordered(listOf(b, crossing, a)))
    }

    @Test
    fun noLinesMeansNoChange() {
        assertEquals(0.42f, Bend.unflatten(PageCurve(emptyList()), 0.3f, 0.42f), 0f)
    }
}
