package dev.olaf.oscanner.feature.capture

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameToPhotoTest {

    @Test
    fun aTallScreensPhotoIsTheMiddleOfTheFrame() {
        // A 3:4 frame shown on a 9:20 screen: the photo is the middle 60% of the frame's width.
        val frame = 0.75f
        val photo = 0.45f
        val quad = Quad(Corner(0.3f, 0.1f), Corner(0.7f, 0.1f), Corner(0.7f, 0.9f), Corner(0.3f, 0.9f))
        val mapped = FrameToPhoto.map(quad, frame, photo)
        assertEquals(1f / 6f, mapped.topLeft.x, 1e-5f)
        assertEquals(5f / 6f, mapped.topRight.x, 1e-5f)
        assertEquals(0.1f, mapped.topLeft.y, 1e-5f)
        // A frame and photo of one shape: nothing moves.
        assertEquals(quad, FrameToPhoto.map(quad, frame, frame))
    }
}
