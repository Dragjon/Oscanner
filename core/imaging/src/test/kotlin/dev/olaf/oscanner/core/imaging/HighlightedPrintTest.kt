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

class HighlightedPrintTest {

    @Test
    fun handwritingRemovalKeepsPrintUnderAHighlighterAndBesideAPhoto() {
        nu.pattern.OpenCV.loadLocally()
        val page = Mat(2200, 1700, CvType.CV_8UC3, Scalar(248.0, 248.0, 246.0))
        val lines = 8
        val printed = mutableListOf<Area>()
        repeat(lines) { i ->
            val y = 300 + i * 60
            // The second line is highlighted in yellow first, as a highlighter goes over print.
            if (i == 1) Imgproc.rectangle(page, Rect(140, y - 34, 900, 46), Scalar(245.0, 240.0, 120.0), -1)
            Imgproc.putText(page, "The committee approved the budget", Point(150.0, y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 1.3, Scalar(25.0, 25.0, 25.0), 2)
            printed += Area(150f / 1700, (y - 32f) / 2200, 900f / 1700, (y + 8f) / 2200)
        }
        // A pale, warm photo: its straight edges look like ruled lines.
        for (x in 0 until 500) {
            val v = (0.78 + 0.18 * x / 500) * 250
            Imgproc.line(page, Point(900.0 + x, 1300.0), Point(900.0 + x, 1599.0), Scalar(v, v * 0.97, v * 0.92))
        }
        // Thin paper: the back page's print shows through, mirrored and soft; light falls off to
        // the left; a phone camera's grain.
        val back = Mat(page.size(), CvType.CV_8UC1, Scalar(255.0))
        repeat(28) { i -> Imgproc.putText(back, "Payment is due within thirty days", Point(500.0, 200.0 + i * 64), Imgproc.FONT_HERSHEY_SIMPLEX, 1.3, Scalar(0.0), 2) }
        Core.flip(back, back, 1)
        Imgproc.GaussianBlur(back, back, org.opencv.core.Size(0.0, 0.0), 2.2)
        val through = Mat()
        back.convertTo(through, CvType.CV_32F, 0.18 / 255, 0.82)
        val light = Mat(page.size(), CvType.CV_32F)
        for (x in 0 until page.cols()) light.colRange(x, x + 1).setTo(Scalar(0.82 + 0.15 * x / page.cols()))
        Core.multiply(through, light, through)
        val shade = Mat()
        Imgproc.cvtColor(through, shade, Imgproc.COLOR_GRAY2RGB)
        val f = Mat()
        page.convertTo(f, CvType.CV_32FC3)
        Core.multiply(f, shade, f)
        val grain = Mat(page.size(), CvType.CV_32FC3)
        Core.randn(grain, 0.0, 2.5)
        Core.add(f, grain, f)
        f.convertTo(page, CvType.CV_8UC3)
        val clean = page.clone()
        // Blue pen notes in the margin.
        repeat(3) { i -> Imgproc.putText(page, "call Sam", Point(1150.0, 360.0 + i * 120), Imgproc.FONT_HERSHEY_SCRIPT_SIMPLEX, 1.6, Scalar(30.0, 60.0, 170.0), 3) }

        for ((withPen, image) in listOf(true to page, false to clean)) {
            Imgproc.GaussianBlur(image, image, org.opencv.core.Size(3.0, 3.0), 0.0)
            for (byLook in listOf(true, false)) {
                val mask = Cleanup.mask(image, PageEdits(removePenInk = true, printed = printed, printByLook = byLook))
                val print = mask?.let { Core.countNonZero(it.submat(Rect(150, 300 - 32, 750, 60 * lines))) } ?: 0
                assertTrue("pen=$withPen byLook=$byLook: $print px of print marked for removal", print < 300)
                if (withPen) assertTrue("byLook=$byLook: the pen wasn't found", Core.countNonZero(mask!!.submat(Rect(1140, 300, 400, 340))) > 2000)
            }
        }
    }
}
