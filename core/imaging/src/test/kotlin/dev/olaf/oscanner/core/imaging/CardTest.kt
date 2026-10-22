package dev.olaf.oscanner.core.imaging

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

class CardTest {

    /**
     * A camera frame (480 × 640, gray) of a light card on a gray desk, with a band darker than
     * the desk across its top and a photo on its left: its sides are lighter than the desk along
     * most of them and darker along the band.
     */
    private fun frame(): Mat {
        val f = Mat(640, 480, CvType.CV_8UC1, Scalar(120.0))
        Imgproc.rectangle(f, Rect(90, 230, 300, 189), Scalar(225.0), -1)
        Imgproc.rectangle(f, Rect(90, 230, 300, 36), Scalar(55.0), -1)
        Imgproc.rectangle(f, Rect(108, 290, 75, 105), Scalar(170.0), -1)
        val noise = Mat(f.size(), CvType.CV_8UC1)
        Core.randn(noise, 0.0, 3.0)
        Core.add(f, noise, f)
        Imgproc.GaussianBlur(f, f, Size(3.0, 3.0), 0.0)
        return f
    }

    @Test
    fun outlinesTheWholeCardBandAndAll() {
        nu.pattern.OpenCV.loadLocally()
        val quad = DocumentDetector(cards = true).detect(frame()).quad
        assertNotNull(quad)
        val truth = listOf(90f to 230f, 390f to 230f, 390f to 419f, 90f to 419f).map { (x, y) -> x / 480 to y / 640 }
        val off = truth.maxOf { (tx, ty) -> quad!!.corners.minOf { hypot(it.x - tx, it.y - ty) } }
        assertTrue("outline off by $off (corners ${quad!!.corners})", off < 0.02f)
    }
}
