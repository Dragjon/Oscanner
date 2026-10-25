package dev.olaf.oscanner.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrintedFaceTest {

    /**
     * Lines of blocky letters (n, o, l, e) [size] px tall, upright strokes [stem] px wide and level
     * ones [bar] px thick, with feet on the stems when [feet]: dark on light, row by row.
     */
    private fun print(size: Int, stem: Int, bar: Int, feet: Boolean): Pair<IntArray, Int> {
        val x = size / 2
        val w = size * 30
        val h = size * 4
        val px = IntArray(w * h) { 245 }
        fun rect(l: Int, t: Int, r: Int, b: Int) {
            for (y in t until b) for (xx in l until r) if (xx in 0 until w && y in 0 until h) px[y * w + xx] = 20
        }
        for (row in 0 until 3) {
            val base = size + row * (size * 4 / 3)
            var left = size / 4
            var k = 0
            while (left + x < w - size) {
                val top = base - x
                when (k++ % 4) {
                    0 -> { rect(left, top, left + stem, base); rect(left + x - stem, top, left + x, base); rect(left, top, left + x, top + bar) }
                    1 -> { rect(left, top, left + stem, base); rect(left + x - stem, top, left + x, base); rect(left, top, left + x, top + bar); rect(left, base - bar, left + x, base) }
                    2 -> rect(left + x / 3, base - size * 3 / 4, left + x / 3 + stem, base)
                    else -> { rect(left, top, left + stem, base); rect(left, top, left + x, top + bar); rect(left, (top + base) / 2, left + x, (top + base) / 2 + bar); rect(left, base - bar, left + x, base) }
                }
                if (feet) rect(left - stem / 2, base - bar, left + stem + stem / 2, base)
                left += x + size / 5
            }
        }
        return px to w
    }

    private fun face(size: Int, stem: Int, bar: Int, feet: Boolean) = PrintedFace().apply {
        val (px, w) = print(size, stem, bar, feet)
        add(px, w, threshold = 130, light = false, size = size.toFloat())
    }

    @Test
    fun thickUprightsAndThinBarsAreSerif() {
        assertEquals(true, face(size = 32, stem = 5, bar = 2, feet = true).serif)
        assertEquals(true, face(size = 48, stem = 7, bar = 3, feet = true).serif)
    }

    @Test
    fun evenStrokesAreSansSerif() {
        assertEquals(false, face(size = 32, stem = 4, bar = 4, feet = false).serif)
        assertEquals(false, face(size = 48, stem = 6, bar = 6, feet = false).serif)
    }

    @Test
    fun smallPrintCantBeTold() {
        assertNull(face(size = 14, stem = 2, bar = 1, feet = true).serif)
    }

    @Test
    fun lightPrintOnDarkIsReadToo() {
        val (px, w) = print(32, 5, 2, true)
        val face = PrintedFace().apply { add(IntArray(px.size) { 255 - px[it] }, w, threshold = 130, light = true, size = 32f) }
        assertEquals(true, face.serif)
    }
}
