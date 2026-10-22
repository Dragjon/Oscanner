package dev.olaf.oscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar

class TurnedFinderTest {

    /** A finder that only knows upright (portrait) frames, and marks whatever is bright. */
    private val uprightOnly = PageFinder { gray ->
        assertTrue("finder fed a sideways frame, ${gray.cols()} × ${gray.rows()}", gray.rows() > gray.cols())
        Mat().also { Core.compare(gray, Scalar(128.0), it, Core.CMP_GT) }
    }

    @Test
    fun aSidewaysFrameIsTurnedUprightAndItsMaskBack() {
        nu.pattern.OpenCV.loadLocally()
        for (degrees in listOf(90, 270)) {
            // A camera frame as the sensor gives it, landscape, with a card in its top left.
            val frame = Mat(480, 640, CvType.CV_8UC1, Scalar(0.0))
            frame.submat(Rect(40, 30, 200, 120)).setTo(Scalar(255.0))
            val mask = uprightOnly.turned(degrees).pageMask(frame)!!
            assertEquals(640, mask.cols())
            assertEquals(480, mask.rows())
            // The card is marked where it is in the frame, not where turning would have put it.
            assertEquals(255.0, mask.get(90, 140)[0], 0.0)
            assertEquals(0.0, mask.get(400, 500)[0], 0.0)
        }
    }

    @Test
    fun anUprightFrameIsLeftAlone() {
        nu.pattern.OpenCV.loadLocally()
        val frame = Mat(640, 480, CvType.CV_8UC1, Scalar(200.0))
        val finder = uprightOnly.turned(0)
        assertTrue(finder === uprightOnly)
        assertEquals(255.0, finder.pageMask(frame)!!.get(10, 10)[0], 0.0)
    }
}
