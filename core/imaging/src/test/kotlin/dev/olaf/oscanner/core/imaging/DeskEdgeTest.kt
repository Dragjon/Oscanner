package dev.olaf.oscanner.core.imaging

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

class DeskEdgeTest {

    /**
     * A camera frame (480 × 640, gray) of a page on a light desk, with a dark mat beginning a
     * little beyond the page's right and bottom sides.
     */
    private fun frame(): Mat {
        val f = Mat(640, 480, CvType.CV_8UC1, Scalar(200.0))
        Imgproc.rectangle(f, Rect(400, 0, 80, 640), Scalar(110.0), -1)
        Imgproc.rectangle(f, Rect(0, 560, 480, 80), Scalar(110.0), -1)
        Imgproc.rectangle(f, Rect(70, 70, 310, 470), Scalar(238.0), -1)
        repeat(22) { i -> Imgproc.line(f, Point(100.0, 110.0 + i * 19), Point(100.0 + 150 + (i * 37) % 110, 110.0 + i * 19), Scalar(60.0), 2) }
        val noise = Mat(f.size(), CvType.CV_8UC1)
        Core.randn(noise, 0.0, 3.0)
        Core.add(f, noise, f)
        Imgproc.GaussianBlur(f, f, Size(3.0, 3.0), 0.0)
        return f
    }

    @Test
    fun outlinesThePageNotOutToTheMatsEdge() {
        nu.pattern.OpenCV.loadLocally()
        val quad = DocumentDetector().detect(frame()).quad
        assertNotNull(quad)
        val truth = listOf(70f to 70f, 380f to 70f, 380f to 540f, 70f to 540f).map { (x, y) -> x / 480 to y / 640 }
        val off = truth.maxOf { (tx, ty) -> quad!!.corners.minOf { hypot(it.x - tx, it.y - ty) } }
        assertTrue("outline off by $off (corners ${quad!!.corners})", off < 0.02f)
    }
}
