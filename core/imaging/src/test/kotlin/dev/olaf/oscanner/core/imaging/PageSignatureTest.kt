package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.random.Random

class PageSignatureTest {

    /** A photo (gray) of a page of text on a desk, the page's line lengths from [seed]. */
    private fun photo(seed: Int, light: Double = 1.0): Mat {
        val img = Mat(1600, 1200, CvType.CV_8UC1, Scalar(90.0 * light))
        Imgproc.rectangle(img, Point(150.0, 150.0), Point(1050.0, 1450.0), Scalar(235.0 * light), -1)
        val r = Random(seed)
        var y = 240.0
        while (y < 1380) {
            if (r.nextFloat() < 0.15f) { y += 45; continue }
            val len = 300 + r.nextInt(500)
            Imgproc.line(img, Point(220.0, y), Point(220.0 + len, y), Scalar(50.0 * light), 9)
            y += 38 + r.nextInt(14)
        }
        val noise = Mat(img.size(), CvType.CV_8UC1)
        Core.randn(noise, 0.0, 4.0)
        Core.add(img, noise, img)
        return img
    }

    private fun quad(j: Float = 0f) = Quad(
        Corner(150f / 1200 + j, 150f / 1600), Corner(1050f / 1200, 150f / 1600 - j),
        Corner(1050f / 1200 - j, 1450f / 1600), Corner(150f / 1200, 1450f / 1600 + j),
    )

    @Test
    fun theSamePageMatchesAndDifferentPagesDont() {
        nu.pattern.OpenCV.loadLocally()
        val first = PageSignature.of(photo(1), quad())
        // The same page again: darker, and a slightly different crop.
        val again = PageSignature.of(photo(1, light = 0.8), quad(0.008f))
        val other = PageSignature.of(photo(2), quad())
        val same = PageSignature.similarity(first, again)
        val different = PageSignature.similarity(first, other)
        assertTrue("same page scored $same", same >= PageSignature.SAME)
        assertTrue("different pages scored $different", different < PageSignature.SAME)
    }
}
