package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
     * A camera frame (480 × 640, BGR) of a pale green card as bright as the gray desk under it,
     * with a dark band across its top and a photo on its left.
     */
    private fun frame(): Mat {
        val f = Mat(640, 480, CvType.CV_8UC3, Scalar(180.0, 180.0, 180.0))
        Imgproc.rectangle(f, Rect(90, 230, 300, 189), Scalar(150.0, 190.0, 170.0), -1)
        Imgproc.rectangle(f, Rect(90, 230, 300, 36), Scalar(120.0, 60.0, 40.0), -1)
        Imgproc.rectangle(f, Rect(108, 290, 75, 105), Scalar(200.0, 200.0, 215.0), -1)
        val noise = Mat(f.size(), CvType.CV_8UC3)
        Core.randn(noise, 0.0, 3.0)
        Core.add(f, noise, f)
        Imgproc.GaussianBlur(f, f, Size(3.0, 3.0), 0.0)
        return f
    }

    @Test
    fun findsAPaleCardOnAGrayDeskByItsColor() {
        nu.pattern.OpenCV.loadLocally()
        val f = frame()
        val gray = Mat().also { Imgproc.cvtColor(f, it, Imgproc.COLOR_BGR2GRAY) }
        val yuv = Mat().also { Imgproc.cvtColor(f, it, Imgproc.COLOR_BGR2YUV) }
        val planes = mutableListOf<Mat>()
        Core.split(yuv, planes)
        val color = Mat().also { DocumentDetector.colorfulness(planes[1], planes[2], it) }
        val detector = DocumentDetector(cards = true)
        val quad = DocumentDetector.wholeCard(detector.detect(gray).quad, detector.detectInColor(color).quad)
        assertNotNull(quad)
        val truth = listOf(90f to 230f, 390f to 230f, 390f to 419f, 90f to 419f).map { (x, y) -> x / 480 to y / 640 }
        val off = truth.maxOf { (tx, ty) -> quad!!.corners.minOf { hypot(it.x - tx, it.y - ty) } }
        assertTrue("outline off by $off (corners ${quad!!.corners})", off < 0.02f)
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = Quad.of(listOf(Corner(l, t), Corner(r, t), Corner(r, b), Corner(l, b)))

    @Test
    fun takesTheOutlineInColorOnlyWhenItHoldsTheOneInGray() {
        val band = box(0.2f, 0.4f, 0.8f, 0.6f)
        val card = box(0.2f, 0.35f, 0.8f, 0.6f)
        assertSame(card, DocumentDetector.wholeCard(band, card))
        val elsewhere = box(0.1f, 0.05f, 0.9f, 0.35f)
        assertSame(band, DocumentDetector.wholeCard(band, elsewhere))
        assertSame(card, DocumentDetector.wholeCard(null, card))
    }
}
