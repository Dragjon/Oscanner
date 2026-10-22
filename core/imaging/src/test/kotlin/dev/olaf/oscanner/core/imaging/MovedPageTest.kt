package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
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

class MovedPageTest {

    /** A photo (480 × 640, gray) of a page on a wooden-gray desk. */
    private fun photo(): Mat {
        val f = Mat(640, 480, CvType.CV_8UC1, Scalar(120.0))
        Imgproc.rectangle(f, Rect(90, 100, 300, 420), Scalar(235.0), -1)
        repeat(18) { i -> Imgproc.line(f, Point(120.0, 140.0 + i * 20), Point(250.0 + (i * 41) % 110, 140.0 + i * 20), Scalar(60.0), 2) }
        val noise = Mat(f.size(), CvType.CV_8UC1)
        Core.randn(noise, 0.0, 3.0)
        Core.add(f, noise, f)
        Imgproc.GaussianBlur(f, f, Size(3.0, 3.0), 0.0)
        return f
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = Quad.of(listOf(Corner(l, t), Corner(r, t), Corner(r, b), Corner(l, b)))

    @Test
    fun takesThePhotosOwnOutlineWhenThePageMovedSinceTheCameraSawIt() {
        nu.pattern.OpenCV.loadLocally()
        // Where the page really is, and where the camera last showed it: 35 px to the left and
        // 45 px up, out over the desk.
        val truth = box(90f / 480, 100f / 640, 390f / 480, 520f / 640)
        val seen = box(55f / 480, 55f / 640, 355f / 480, 475f / 640)
        val quad = DocumentDetector().inPhoto(photo(), seen)
        assertNotNull(quad)
        val off = truth.corners.maxOf { t -> quad!!.corners.minOf { hypot(it.x - t.x, it.y - t.y) } }
        assertTrue("outline off by $off (corners ${quad!!.corners})", off < 0.02f)
    }
}
