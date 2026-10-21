package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Area
import dev.olaf.oscanner.core.model.PageEdits
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class CleanupShapesTest {

    @Test
    fun handwritingRemovalLeavesAChartsBarsAlone() {
        nu.pattern.OpenCV.loadLocally()
        val page = Mat(2200, 1700, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        // Print, so the page has something sure to compare against.
        repeat(6) { i -> Imgproc.putText(page, "The club planted forty new trees", Point(150.0, 300.0 + i * 52), Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, Scalar(0.0, 0.0, 0.0), 2) }
        val printed = List(6) { i -> Area(150f / 1700, (270f + i * 52) / 2200, 900f / 1700, (310f + i * 52) / 2200) }
        // A bar chart in red, blue and green (RGB), like the test page that lost three bars.
        val bars = listOf(Scalar(220.0, 80.0, 60.0), Scalar(60.0, 130.0, 200.0), Scalar(80.0, 170.0, 90.0))
        val barRects = bars.mapIndexed { i, colour ->
            Rect(500 + i * 180, 900, 120, 400).also { Imgproc.rectangle(page, it, colour, -1) }
        }
        // Blue ballpoint writing in the margin: thin strokes, which should still go.
        for (k in 0 until 8) {
            Imgproc.line(page, Point(200.0 + k * 40, 1700.0), Point(220.0 + k * 40, 1660.0), Scalar(30.0, 50.0, 170.0), 4)
        }

        val mask = Cleanup.mask(page, PageEdits(removePenInk = true, printed = printed))!!
        for (r in barRects) {
            val inside = mask.submat(Rect(r.x - 2, r.y - 2, r.width + 4, r.height + 4))
            assertTrue("a bar was marked for removal", Core.countNonZero(inside) == 0)
        }
        assertTrue("the pen strokes weren't removed", Core.countNonZero(mask.submat(Rect(190, 1650, 340, 60))) > 500)
    }
}
