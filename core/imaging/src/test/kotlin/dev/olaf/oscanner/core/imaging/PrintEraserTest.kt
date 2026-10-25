package dev.olaf.oscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.random.Random

class PrintEraserTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = nu.pattern.OpenCV.loadLocally()
    }

    /** A sunset-like gradient with grain and soft blotches, as a photo behind a caption. */
    private fun picture(w: Int, h: Int): Mat {
        val m = Mat(h, w, CvType.CV_8UC3)
        val r = Random(9)
        val px = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val t = x.toDouble() / w
            val n = r.nextInt(-14, 14)
            val i = (y * w + x) * 3
            px[i] = (230 - 60 * t + n).toInt().coerceIn(0, 255).toByte()
            px[i + 1] = (150 + 40 * t + n).toInt().coerceIn(0, 255).toByte()
            px[i + 2] = (90 + 120 * t + n).toInt().coerceIn(0, 255).toByte()
        }
        m.put(0, 0, px)
        repeat(12) { Imgproc.circle(m, Point(r.nextInt(w).toDouble(), r.nextInt(h).toDouble()), 30 + r.nextInt(40), Scalar(200.0, 120.0 + r.nextInt(40), 140.0), -1) }
        Imgproc.GaussianBlur(m, m, org.opencv.core.Size(9.0, 9.0), 0.0)
        return m
    }

    @Test
    fun printOnAPictureGoesAndThePictureStays() {
        val m = picture(900, 400)
        val clean = m.clone()
        val box = Rect(100, 140, 700, 120)
        listOf("Sunset over the bay", "Photo: harbour office").forEachIndexed { i, line ->
            Imgproc.putText(m, line, Point(110.0, 190.0 + i * 55), Imgproc.FONT_HERSHEY_SIMPLEX, 1.5, Scalar(25.0, 25.0, 30.0), 3)
        }
        PrintEraser.erase(m, box, Scalar(25.0, 25.0, 30.0), 40.0)
        // No print left: nothing in the box is anywhere near as dark as the ink.
        val gray = Mat()
        Imgproc.cvtColor(m.submat(box), gray, Imgproc.COLOR_RGB2GRAY)
        val dark = Mat()
        Imgproc.threshold(gray, dark, 80.0, 255.0, Imgproc.THRESH_BINARY_INV)
        assertTrue("dark pixels left: ${Core.countNonZero(dark)}", Core.countNonZero(dark) < 50)
        // And the picture's there: its colors as before, its grain too, not a flat patch.
        val mean = MatOfDouble()
        val spread = MatOfDouble()
        Core.meanStdDev(m.submat(box), mean, spread)
        val cleanMean = MatOfDouble()
        val cleanSpread = MatOfDouble()
        Core.meanStdDev(clean.submat(box), cleanMean, cleanSpread)
        for (c in 0..2) assertEquals("channel $c", cleanMean.toArray()[c], mean.toArray()[c], 10.0)
        assertTrue("grain ${spread.toArray()[1]} vs ${cleanSpread.toArray()[1]}", spread.toArray()[1] > 0.6 * cleanSpread.toArray()[1])
    }

    @Test
    fun theRimSharpeningLeavesGoesToo() {
        val m = picture(900, 400)
        val clean = m.clone()
        val box = Rect(100, 140, 700, 120)
        listOf("Sunset over the bay", "Photo: harbour office").forEachIndexed { i, line ->
            val at = Point(110.0, 190.0 + i * 55)
            // A pale rim round dark letters, as sharpening leaves.
            Imgproc.putText(m, line, at, Imgproc.FONT_HERSHEY_SIMPLEX, 1.5, Scalar(250.0, 245.0, 240.0), 13)
            Imgproc.putText(m, line, at, Imgproc.FONT_HERSHEY_SIMPLEX, 1.5, Scalar(25.0, 25.0, 30.0), 3)
        }
        PrintEraser.erase(m, box, Scalar(25.0, 25.0, 30.0), 40.0)
        // Hardly anything in the box is far from the picture as it was without the print.
        val a = Mat()
        val b = Mat()
        Imgproc.cvtColor(m.submat(box), a, Imgproc.COLOR_RGB2GRAY)
        Imgproc.cvtColor(clean.submat(box), b, Imgproc.COLOR_RGB2GRAY)
        val diff = Mat()
        Core.absdiff(a, b, diff)
        Imgproc.threshold(diff, diff, 45.0, 255.0, Imgproc.THRESH_BINARY)
        val off = Core.countNonZero(diff).toDouble() / box.area()
        assertTrue("off the picture: $off", off < 0.005)
    }
}
