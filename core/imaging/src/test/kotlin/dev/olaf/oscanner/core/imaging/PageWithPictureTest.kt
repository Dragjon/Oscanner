package dev.olaf.oscanner.core.imaging

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
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

/**
 * A page with a big picture printed on it: the paper beside the picture is outlined well too,
 * sharing the page's sides, and mustn't be taken for a sheet of its own.
 */
class PageWithPictureTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = nu.pattern.OpenCV.loadLocally()
    }

    /** A camera frame (480 × 640, gray) of a page on a dark desk; [pictureAt] where on the page the picture is, top to bottom. */
    private fun frame(pictureAt: Int): Mat {
        val f = Mat(640, 480, CvType.CV_8UC1, Scalar(90.0))
        val page = Rect(60, 60, 360, 520)
        Imgproc.rectangle(f, page, Scalar(240.0), -1)
        val picture = Rect(80, pictureAt, 320, 200)
        // A photo: shaded from dark to mid, with a few blobs.
        for (x in 0 until picture.width) {
            Imgproc.line(f, Point(picture.x + x.toDouble(), picture.y.toDouble()), Point(picture.x + x.toDouble(), picture.y + picture.height - 1.0), Scalar(70.0 + 90.0 * x / picture.width))
        }
        listOf(Point(150.0, pictureAt + 60.0), Point(300.0, pictureAt + 130.0)).forEach { Imgproc.circle(f, it, 35, Scalar(200.0), -1) }
        // Lines of text in the rest.
        var y = 90
        while (y < 560) {
            if (y !in pictureAt - 10..pictureAt + picture.height + 10) Imgproc.line(f, Point(90.0, y.toDouble()), Point(370.0 - (y * 7) % 90, y.toDouble()), Scalar(60.0), 2)
            y += 18
        }
        val noise = Mat(f.size(), CvType.CV_8UC1)
        Core.randn(noise, 0.0, 3.0)
        Core.add(f, noise, f)
        Imgproc.GaussianBlur(f, f, Size(3.0, 3.0), 0.0)
        return f
    }

    @Test
    fun aPageWithAPictureIsFoundWhole() {
        val truth = listOf(60f to 60f, 420f to 60f, 420f to 580f, 60f to 580f).map { (x, y) -> x / 480 to y / 640 }
        for (at in listOf(80, 220, 360)) {
            val quad = DocumentDetector().detect(frame(at)).quad
            assertNotNull("picture at $at: no outline", quad)
            val off = truth.maxOf { (tx, ty) -> quad!!.corners.minOf { hypot(it.x - tx, it.y - ty) } }
            assertTrue("picture at $at: outline off by $off (corners ${quad!!.corners})", off < 0.02f)
        }
    }
}
