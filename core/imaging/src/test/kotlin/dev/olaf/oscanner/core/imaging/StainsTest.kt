package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.PageEdits
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

class StainsTest {

    private val paperColour = Scalar(248.0, 247.0, 244.0)

    /** A page of print with [tint] multiplied over it (1 = no change). */
    private fun page(paper: Scalar = paperColour, decorate: (Mat) -> Unit = {}, tint: (Mat) -> Unit = {}): Mat {
        val page = Mat(2200, 1700, CvType.CV_8UC3, paper)
        repeat(20) { i -> Imgproc.putText(page, "The committee approved the budget for the library", Point(120.0, 200.0 + i * 70), Imgproc.FONT_HERSHEY_SIMPLEX, 1.25, Scalar(25.0, 25.0, 25.0), 2) }
        decorate(page)
        val t = Mat(page.size(), CvType.CV_32FC3, Scalar(1.0, 1.0, 1.0))
        tint(t)
        Imgproc.GaussianBlur(t, t, Size(0.0, 0.0), 4.0)
        val f = Mat()
        page.convertTo(f, CvType.CV_32FC3)
        Core.multiply(f, t, f)
        f.convertTo(page, CvType.CV_8UC3)
        return page
    }

    private fun mean(img: Mat, r: Rect) = Core.mean(img.submat(r)).`val`

    /** How far, at most per channel, an area's mean is from the paper's color. */
    private fun offPaper(img: Mat, r: Rect) = mean(img, r).take(3).zip(paperColour.`val`.take(3)).maxOf { (a, b) -> kotlin.math.abs(a - b) }

    @Test
    fun takesCoffeeAndTeaStainsOutAndKeepsThePrintUnderThem() {
        nu.pattern.OpenCV.loadLocally()
        val stained = page(decorate = { p -> Imgproc.putText(p, "budget", Point(1395.0, 630.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.25, Scalar(25.0, 25.0, 25.0), 2) }) { t ->
            // A coffee drop with a darker rim, over a word in the margin, and a big tea stain.
            Imgproc.circle(t, Point(1450.0, 620.0), 60, Scalar(0.82, 0.72, 0.6), -1)
            Imgproc.circle(t, Point(1450.0, 620.0), 60, Scalar(0.7, 0.58, 0.45), 6)
            Imgproc.ellipse(t, Point(500.0, 1600.0), Size(380.0, 260.0), 20.0, 0.0, 360.0, Scalar(0.9, 0.84, 0.72), -1)
        }
        val before = offPaper(stained, Rect(450, 1700, 120, 60))
        Cleanup.apply(stained, PageEdits(removeStains = true))
        // Clear paper inside both stains is paper again.
        val tea = offPaper(stained, Rect(450, 1700, 120, 60))
        assertTrue("tea stain left: $tea levels off paper (was $before)", tea < 6)
        val drop = offPaper(stained, Rect(1420, 645, 60, 20))
        assertTrue("coffee drop left: $drop levels off paper", drop < 8)
        val rim = offPaper(stained, Rect(1440, 556, 20, 8))
        assertTrue("coffee rim left: $rim levels off paper", rim < 15)
        // The print under the drop stays print: its darkest pixels are still dark.
        val under = Mat()
        Imgproc.cvtColor(stained.submat(Rect(1395, 600, 110, 35)), under, Imgproc.COLOR_RGB2GRAY)
        assertTrue("print under the stain faded: ${Core.minMaxLoc(under).minVal}", Core.minMaxLoc(under).minVal < 80)
    }

    @Test
    fun leavesHighlighterPhotosAndWarmPaperAlone() {
        nu.pattern.OpenCV.loadLocally()
        val highlighted = page(decorate = { p ->
            Imgproc.rectangle(p, Rect(110, 236, 900, 46), Scalar(245.0, 240.0, 120.0), -1)
            Imgproc.putText(p, "The committee approved the budget for the library", Point(120.0, 270.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.25, Scalar(25.0, 25.0, 25.0), 2)
            // A warm photo with texture: a wood grain.
            for (y in 0 until 300) Imgproc.line(p, Point(1100.0, 1500.0 + y), Point(1500.0, 1500.0 + y), Scalar(180.0 + 30 * Math.sin(y / 3.0), 140.0 + 25 * Math.sin(y / 3.0), 100.0), 1)
        })
        val copy = highlighted.clone()
        Cleanup.apply(copy, PageEdits(removeStains = true))
        val diff = Mat()
        Core.absdiff(copy, highlighted, diff)
        Imgproc.cvtColor(diff, diff, Imgproc.COLOR_RGB2GRAY)
        Imgproc.threshold(diff, diff, 12.0, 255.0, Imgproc.THRESH_BINARY)
        assertTrue("changed ${Core.countNonZero(diff)} pixels of a page with no stains", Core.countNonZero(diff) < 2000)

        // A whole page of old, yellowed paper is the paper, not a stain.
        val warm = page(paper = Scalar(238.0, 226.0, 196.0))
        val warmCopy = warm.clone()
        Cleanup.apply(warmCopy, PageEdits(removeStains = true))
        Core.absdiff(warmCopy, warm, diff)
        Imgproc.cvtColor(diff, diff, Imgproc.COLOR_RGB2GRAY)
        Imgproc.threshold(diff, diff, 12.0, 255.0, Imgproc.THRESH_BINARY)
        assertTrue("changed ${Core.countNonZero(diff)} pixels of yellowed paper", Core.countNonZero(diff) < 2000)
    }
}
