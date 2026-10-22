package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Test

class BookPagesTest {

    private fun box(l: Float, t: Float, r: Float, b: Float) = Quad.of(listOf(Corner(l, t), Corner(r, t), Corner(r, b), Corner(l, b)))

    private val left = box(0.1f, 0.2f, 0.5f, 0.8f)
    private val right = box(0.5f, 0.2f, 0.9f, 0.8f)
    private val upper = box(0.2f, 0.1f, 0.8f, 0.5f)
    private val lower = box(0.2f, 0.5f, 0.8f, 0.9f)

    @Test
    fun anUprightBookReadsLeftThenRight() {
        assertEquals(left to right, BookPages.inReadingOrder(left to right, 0))
        assertEquals(left to right, BookPages.inReadingOrder(right to left, 0))
    }

    @Test
    fun anUpsideDownBookReadsItsRightHalfFirst() {
        assertEquals(right to left, BookPages.inReadingOrder(left to right, 180))
    }

    @Test
    fun aTurnedBookReadsFromWhicheverEndComesLeft() {
        // Turned back clockwise, the upper page goes to the right: the lower is read first.
        assertEquals(lower to upper, BookPages.inReadingOrder(upper to lower, 90))
        // Turned back the other way, the upper page goes to the left.
        assertEquals(upper to lower, BookPages.inReadingOrder(upper to lower, 270))
    }
}
