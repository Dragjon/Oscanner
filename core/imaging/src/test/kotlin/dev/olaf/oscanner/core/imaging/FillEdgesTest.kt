package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.PageEdits
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

class FillEdgesTest {

    private val paper = Scalar(244.0, 242.0, 236.0)
    private val desk = Scalar(120.0, 92.0, 70.0)

    private fun mean(img: Mat, r: Rect) = Core.mean(img.submat(r)).`val`
    private fun isPaper(img: Mat, r: Rect) = mean(img, r).take(3).zip(paper.`val`.take(3)).all { (a, b) -> abs(a - b) < 12 }

    @Test
    fun fillsDeskInTheCornersAndAlongAnEdgeButNotPrint() {
        nu.pattern.OpenCV.loadLocally()
        val page = Mat(2200, 1700, CvType.CV_8UC3, paper)
        repeat(30) { i -> Imgproc.putText(page, "The committee approved the budget", Point(140.0, 220.0 + i * 60), Imgproc.FONT_HERSHEY_SIMPLEX, 1.3, Scalar(30.0, 30.0, 30.0), 2) }
        // A photo printed to the page's right edge, well past the edge band.
        Imgproc.rectangle(page, Rect(1300, 900, 400, 500), Scalar(60.0, 110.0, 160.0), -1)
        // Desk: the top-left corner folded off, the bottom-right corner a square of it, a strip down the left.
        Imgproc.fillPoly(page, listOf(MatOfPoint(Point(0.0, 0.0), Point(140.0, 0.0), Point(0.0, 140.0))), desk)
        Imgproc.rectangle(page, Rect(1600, 2100, 100, 100), desk, -1)
        Imgproc.rectangle(page, Rect(0, 600, 18, 900), desk, -1)

        val noise = Mat(page.size(), CvType.CV_8UC3)
        Core.randn(noise, 0.0, 2.0)
        Core.add(page, noise, page)

        Cleanup.apply(page, PageEdits(fillEdges = true))

        assertTrue("folded corner left: ${mean(page, Rect(5, 5, 40, 40)).toList()}", isPaper(page, Rect(5, 5, 40, 40)))
        assertTrue("square corner left: ${mean(page, Rect(1620, 2120, 60, 60)).toList()}", isPaper(page, Rect(1620, 2120, 60, 60)))
        assertTrue("edge strip left: ${mean(page, Rect(2, 700, 12, 600)).toList()}", isPaper(page, Rect(2, 700, 12, 600)))
        // The photo stays, and so does the text.
        val photo = mean(page, Rect(1400, 1000, 250, 300))
        assertTrue("photo filled: ${photo.toList()}", photo[2] > 140 && photo[0] < 80)
        val text = Mat()
        Imgproc.cvtColor(page.submat(Rect(140, 190, 700, 40)), text, Imgproc.COLOR_RGB2GRAY)
        assertTrue("text filled", Core.minMaxLoc(text).minVal < 80)
    }
}
