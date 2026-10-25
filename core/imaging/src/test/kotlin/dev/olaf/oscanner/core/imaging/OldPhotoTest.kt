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
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

class OldPhotoTest {

    /** A photo-like scene: sky, a field, a red house, a gray wall, with soft texture. */
    private fun scene(): Mat {
        val img = Mat(1200, 1600, CvType.CV_8UC3)
        for (y in 0 until 1200) {
            val c = if (y < 600) Scalar(110.0 + y * 0.1, 160.0 + y * 0.05, 230.0) else Scalar(70.0, 140.0 - (y - 600) * 0.05, 50.0)
            img.row(y).setTo(c)
        }
        Imgproc.rectangle(img, Rect(300, 400, 400, 350), Scalar(190.0, 40.0, 35.0), -1)
        Imgproc.rectangle(img, Rect(1000, 450, 400, 400), Scalar(128.0, 128.0, 128.0), -1)
        Imgproc.rectangle(img, Rect(1050, 500, 80, 80), Scalar(20.0, 20.0, 20.0), -1)
        val noise = Mat(img.size(), CvType.CV_8UC3)
        Core.randn(noise, 0.0, 4.0)
        Core.add(img, noise, img)
        Imgproc.GaussianBlur(img, img, Size(3.0, 3.0), 0.0)
        return img
    }

    /** Faded: contrast halved toward a yellowish white, as old prints go. */
    private fun fade(img: Mat): Mat {
        val f = Mat()
        img.convertTo(f, CvType.CV_32FC3)
        Core.multiply(f, Scalar(0.55, 0.55, 0.55), f)
        Core.add(f, Scalar(0.45 * 235, 0.45 * 215, 0.45 * 160), f)
        val out = Mat()
        f.convertTo(out, CvType.CV_8UC3)
        return out
    }

    private fun mean(img: Mat, r: Rect) = Core.mean(img.submat(r)).`val`

    @Test
    fun restoresFadedColorsAndTakesOutDust() {
        nu.pattern.OpenCV.loadLocally()
        val clean = scene()
        val old = fade(clean)
        // White and black dust, and a white scratch across the sky.
        val dust = listOf(Point(200.0, 200.0), Point(800.0, 300.0), Point(500.0, 900.0), Point(1300.0, 1000.0), Point(1200.0, 700.0))
        dust.forEachIndexed { i, p -> Imgproc.circle(old, p, 3, if (i % 2 == 0) Scalar(250.0, 250.0, 245.0) else Scalar(15.0, 15.0, 15.0), -1) }
        Imgproc.line(old, Point(100.0, 120.0), Point(700.0, 260.0), Scalar(250.0, 250.0, 245.0), 2)

        val out = Filters.apply(old, PageFilter.Photo)

        // The gray wall is gray again, not yellow.
        val wall = mean(out, Rect(1200, 650, 150, 150))
        assertTrue("wall still tinted: ${wall.toList()}", abs(wall[0] - wall[2]) < 18)
        // Contrast is back: the house's red stands out from the field again.
        val house = mean(out, Rect(400, 500, 200, 150))
        assertTrue("house still faded: ${house.toList()}", house[0] - house[1] > 110)
        // The dust and the scratch are gone: those spots look like what's around them.
        for (p in dust) {
            val spot = mean(out, Rect(p.x.toInt() - 2, p.y.toInt() - 2, 5, 5))
            val near = mean(out, Rect(p.x.toInt() + 12, p.y.toInt() - 2, 5, 5))
            assertTrue("dust at $p left: ${spot.toList()} vs ${near.toList()}", (0..2).all { abs(spot[it] - near[it]) < 25 })
        }
        val onScratch = mean(out, Rect(398, 189, 4, 3))
        val besideIt = mean(out, Rect(398, 205, 4, 3))
        assertTrue("scratch left: ${onScratch.toList()} vs ${besideIt.toList()}", (0..2).all { abs(onScratch[it] - besideIt[it]) < 25 })
        // The black window in the wall isn't dust: it stays dark.
        assertTrue(mean(out, Rect(1070, 520, 40, 40))[0] < 60)
    }

    @Test
    fun takesOutFaintDustAndShortScratchesOnPlainSky() {
        nu.pattern.OpenCV.loadLocally()
        val old = fade(scene())
        // On a faded sky, as pale as the paper, dust and scratches stand out by only 20 or so
        // levels; stretching the colors back makes them plain to see.
        fun paler(p: Point): Scalar {
            val m = Core.mean(old.submat(Rect(p.x.toInt() - 6, p.y.toInt() - 6, 12, 12))).`val`
            return Scalar(m[0] + 22, m[1] + 22, m[2] + 22)
        }
        val dust = listOf(Point(250.0, 150.0), Point(900.0, 220.0), Point(1350.0, 330.0), Point(650.0, 80.0))
        dust.forEach { Imgproc.circle(old, it, 2, paler(it), -1) }
        // A short scratch, 3% of the photo's width, a pixel wide.
        Imgproc.line(old, Point(1100.0, 100.0), Point(1104.0, 150.0), paler(Point(1102.0, 125.0)), 1)
        // A white pole against the sky is the picture's own: long, and it stays.
        Imgproc.line(old, Point(820.0, 330.0), Point(820.0, 600.0), Scalar(250.0, 248.0, 240.0), 3)

        val out = Filters.apply(old, PageFilter.Photo)

        for (p in dust) {
            val spot = mean(out, Rect(p.x.toInt() - 1, p.y.toInt() - 1, 3, 3))
            val near = mean(out, Rect(p.x.toInt() + 10, p.y.toInt() - 1, 3, 3))
            assertTrue("faint dust at $p left: ${spot.toList()} vs ${near.toList()}", (0..2).all { abs(spot[it] - near[it]) < 10 })
        }
        val onScratch = mean(out, Rect(1102, 120, 1, 10))
        val besideIt = mean(out, Rect(1112, 120, 1, 10))
        assertTrue("short scratch left: ${onScratch.toList()} vs ${besideIt.toList()}", (0..2).all { abs(onScratch[it] - besideIt[it]) < 10 })
        val pole = mean(out, Rect(819, 400, 3, 100))
        val sky = mean(out, Rect(840, 400, 3, 100))
        assertTrue("pole lost: ${pole.toList()} vs ${sky.toList()}", pole[0] - sky[0] > 40)
    }
}
