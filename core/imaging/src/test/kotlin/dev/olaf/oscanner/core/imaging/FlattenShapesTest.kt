package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.PageFilter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class FlattenShapesTest {

    /** A page with a chart and a black box, under a soft shadow over its left side. */
    private fun page(): Mat {
        val page = Mat(2200, 1700, CvType.CV_8UC3, Scalar(250.0, 250.0, 248.0))
        repeat(6) { i -> Imgproc.putText(page, "The club planted forty new trees", Point(150.0, 300.0 + i * 52), Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, Scalar(20.0, 20.0, 20.0), 2) }
        listOf(Scalar(220.0, 80.0, 60.0), Scalar(60.0, 130.0, 200.0), Scalar(80.0, 170.0, 90.0)).forEachIndexed { i, c ->
            Imgproc.rectangle(page, Rect(900 + i * 180, 900, 120, 400), c, -1)
        }
        Imgproc.rectangle(page, Rect(300, 1600, 500, 120), Scalar(25.0, 25.0, 25.0), -1)
        // Shadow: darkest (x0.65) at the left edge, fading out by a third of the way across.
        val shade = Mat(page.size(), CvType.CV_32FC3)
        for (x in 0 until page.cols()) {
            val f = 0.65 + 0.35 * (x / 560.0).coerceAtMost(1.0)
            shade.colRange(x, x + 1).setTo(Scalar(f, f, f))
        }
        val f = Mat()
        page.convertTo(f, CvType.CV_32FC3)
        Core.multiply(f, shade, f)
        f.convertTo(page, CvType.CV_8UC3)
        return page
    }

    private fun mean(img: Mat, r: Rect) = Core.mean(img.submat(r)).`val`

    @Test
    fun grayAndAutoKeepSolidShapesSolidAndStillLiftTheShadow() {
        nu.pattern.OpenCV.loadLocally()
        val src = page()
        val bar = Rect(930, 950, 60, 300)
        val box = Rect(350, 1620, 400, 80)
        val shadowedPaper = Rect(20, 1900, 200, 200)

        val gray = Filters.apply(src, PageFilter.Gray)
        assertTrue("Gray hollowed the bar: ${mean(gray, bar)[0]}", mean(gray, bar)[0] < 170)
        assertTrue("Gray hollowed the black box: ${mean(gray, box)[0]}", mean(gray, box)[0] < 90)
        assertTrue("Gray left the shadow: ${mean(gray, shadowedPaper)[0]}", mean(gray, shadowedPaper)[0] > 225)

        val auto = Filters.apply(src, PageFilter.Auto)
        val red = mean(auto, bar)
        assertTrue("Auto washed out the red bar: ${red.toList()}", red[0] > 150 && red[1] < 140)
        assertTrue("Auto left the shadow: ${mean(auto, shadowedPaper)[0]}", mean(auto, shadowedPaper)[0] > 225)

        val ink = Filters.apply(src, PageFilter.Ink)
        assertTrue("Ink hollowed the bar: ${mean(ink, bar)[0]}", mean(ink, bar)[0] < 30)
        assertTrue("Ink hollowed the black box: ${mean(ink, box)[0]}", mean(ink, box)[0] < 30)
        assertTrue("Ink left the shadow: ${mean(ink, shadowedPaper)[0]}", mean(ink, shadowedPaper)[0] > 240)
    }
}
