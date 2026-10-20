package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadGeometryTest {

    private fun quad(vararg xy: Float) = Quad.of(xy.toList().chunked(2) { Corner(it[0], it[1]) })

    @Test
    fun orderPutsTopLeftFirstAndGoesClockwise() {
        val shuffled = listOf(Corner(0.9f, 0.8f), Corner(0.1f, 0.1f), Corner(0.1f, 0.9f), Corner(0.8f, 0.2f))
        val q = QuadGeometry.order(shuffled)
        assertEquals(Corner(0.1f, 0.1f), q.topLeft)
        assertEquals(Corner(0.8f, 0.2f), q.topRight)
        assertEquals(Corner(0.9f, 0.8f), q.bottomRight)
        assertEquals(Corner(0.1f, 0.9f), q.bottomLeft)
    }

    @Test
    fun iouOfIdenticalQuadsIsOne() {
        val q = quad(0.1f, 0.1f, 0.9f, 0.15f, 0.85f, 0.9f, 0.12f, 0.88f)
        assertEquals(1.0, QuadGeometry.iou(q, q), 1e-6)
    }

    @Test
    fun iouOfHalfOverlappingSquares() {
        val a = quad(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val b = quad(0.5f, 0f, 1.5f, 0f, 1.5f, 1f, 0.5f, 1f)
        // Intersection 0.5, union 1.5.
        assertEquals(1.0 / 3.0, QuadGeometry.iou(a, b), 1e-6)
    }

    @Test
    fun iouOfDisjointQuadsIsZero() {
        val a = quad(0f, 0f, 0.2f, 0f, 0.2f, 0.2f, 0f, 0.2f)
        val b = quad(0.5f, 0.5f, 0.7f, 0.5f, 0.7f, 0.7f, 0.5f, 0.7f)
        assertEquals(0.0, QuadGeometry.iou(a, b), 1e-9)
    }

    @Test
    fun convexityRejectsBowTie() {
        assertTrue(QuadGeometry.isConvex(Quad.Full))
        val bowTie = quad(0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f)
        assertFalse(QuadGeometry.isConvex(bowTie))
    }

    @Test
    fun anglesOfRectangleAreRight() {
        QuadGeometry.angles(Quad.Full).forEach { assertEquals(90.0, it, 1e-6) }
    }

    @Test
    fun rotate90MovesTopLeftToTopRight() {
        // A small box near the buffer's top-left ends up near the top-right after a clockwise turn.
        val q = quad(0.1f, 0.1f, 0.3f, 0.1f, 0.3f, 0.2f, 0.1f, 0.2f)
        val r = QuadGeometry.rotate(q, 90)
        assertEquals(0.8f, r.topLeft.x, 1e-6f)
        assertEquals(0.1f, r.topLeft.y, 1e-6f)
        assertEquals(0.9f, r.bottomRight.x, 1e-6f)
        assertEquals(0.3f, r.bottomRight.y, 1e-6f)
    }

    @Test
    fun rotateFourTimesIsIdentity() {
        val q = quad(0.1f, 0.2f, 0.7f, 0.1f, 0.8f, 0.9f, 0.2f, 0.8f)
        var r = q
        repeat(4) { r = QuadGeometry.rotate(r, 90) }
        q.corners.zip(r.corners).forEach { (a, b) ->
            assertEquals(a.x, b.x, 1e-5f)
            assertEquals(a.y, b.y, 1e-5f)
        }
    }

    @Test
    fun warpSizeAveragesOppositeEdges() {
        val q = quad(0f, 0f, 0.5f, 0f, 0.5f, 1f, 0f, 1f)
        assertEquals(500 to 2000, QuadGeometry.warpSize(q, 1000, 2000))
    }

    private val tilted = Quad(Corner(0.12f, 0.08f), Corner(0.91f, 0.15f), Corner(0.85f, 0.95f), Corner(0.05f, 0.88f))

    private fun assertNear(expected: Corner, actual: Corner) {
        assertEquals(expected.x, actual.x, 1e-4f)
        assertEquals(expected.y, actual.y, 1e-4f)
    }

    @Test
    fun pageCornersLandOnTheQuadCorners() {
        assertNear(tilted.topLeft, QuadGeometry.pageToSource(tilted, 0, Corner(0f, 0f)))
        assertNear(tilted.bottomRight, QuadGeometry.pageToSource(tilted, 0, Corner(1f, 1f)))
        // Turned a quarter clockwise, the page's top-left shows what was the bottom-left.
        assertNear(tilted.bottomLeft, QuadGeometry.pageToSource(tilted, 90, Corner(0f, 0f)))
        assertNear(tilted.topRight, QuadGeometry.pageToSource(tilted, 270, Corner(0f, 0f)))
    }

    @Test
    fun pageAndSourceMapsUndoEachOther() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val p = Corner(0.3f, 0.7f)
            val back = QuadGeometry.sourceToPage(tilted, rotation, QuadGeometry.pageToSource(tilted, rotation, p))
            assertNear(p, back)
        }
    }

    @Test
    fun containsUsesTheOutline() {
        val square = listOf(Corner(0.2f, 0.2f), Corner(0.6f, 0.2f), Corner(0.6f, 0.6f), Corner(0.2f, 0.6f))
        assertTrue(QuadGeometry.contains(square, Corner(0.4f, 0.4f)))
        assertFalse(QuadGeometry.contains(square, Corner(0.7f, 0.4f)))
    }
}
