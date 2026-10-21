package dev.olaf.oscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlideShapeTest {

    @Test
    fun nearShapesSnap() {
        assertEquals(16f / 9f, SlideShape.snap(1700, 1000)!!, 1e-4f)
        assertEquals(16f / 9f, SlideShape.snap(1000, 1800)!!, 1e-4f)
        assertEquals(4f / 3f, SlideShape.snap(1300, 1000)!!, 1e-4f)
        assertEquals(16f / 10f, SlideShape.snap(1610, 1000)!!, 1e-4f)
    }

    @Test
    fun otherShapesStayAsMeasured() {
        assertNull(SlideShape.snap(1414, 1000))
        assertNull(SlideShape.snap(1000, 1000))
        assertNull(SlideShape.snap(0, 10))
    }
}
