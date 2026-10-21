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

class ShowThroughTest {

    private fun text(lines: List<String>, x: Int, top: Int, pitch: Int, thickness: Int): Mat {
        val m = Mat(2200, 1700, CvType.CV_8UC1, Scalar(255.0))
        lines.forEachIndexed { i, t -> Imgproc.putText(m, t, Point(x.toDouble(), (top + i * pitch).toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 1.3, Scalar(0.0), thickness) }
        return m
    }

    @Test
    fun removesTheBackPageButNotPencilHighlighterOrPhotos() {
        nu.pattern.OpenCV.loadLocally()
        val front = text(List(4) { "The committee approved the budget" } + List(8) { "" } + List(5) { "Members met every Tuesday" }, 150, 200, 56, 2)
        // The back page: mirrored, blurred by the paper, about 18% dark where solid.
        val back = text(List(30) { "Payment is due within thirty days" }, 170, 180, 60, 2)
        Core.flip(back, back, 1)
        Imgproc.GaussianBlur(back, back, Size(0.0, 0.0), 2.2)
        // Pencil on the front: thin, sharp, light gray, in the blank middle.
        val pencil = text(List(6) { "" } + "pencil: call Sam today", 200, 200, 56, 1)

        val f = Mat(); front.convertTo(f, CvType.CV_32F, 1.0 / 255)
        val b = Mat(); back.convertTo(b, CvType.CV_32F, 1.0 / 255)
        val p = Mat(); pencil.convertTo(p, CvType.CV_32F, 1.0 / 255)
        // page = front * (1 - 0.18 (1 - back)), then pencil at 68% dark.
        val shade = Mat(); b.convertTo(shade, -1, 0.18, 0.82)
        val page = Mat(); Core.multiply(f, shade, page)
        val pen = Mat(); p.convertTo(pen, -1, 0.32, 0.68)
        Core.min(page, pen, page)
        val gray = Mat(); page.convertTo(gray, CvType.CV_8U, 255.0)
        val rgb = Mat(); Imgproc.cvtColor(gray, rgb, Imgproc.COLOR_GRAY2RGB)
        // A pale photo (a smooth gradient) and a yellow highlighter stripe over the first line.
        val photo = Rect(900, 1300, 500, 300)
        for (x in 0 until photo.width) {
            val v = (0.78 + 0.18 * x / photo.width) * 250
            Imgproc.line(rgb, Point((photo.x + x).toDouble(), photo.y.toDouble()), Point((photo.x + x).toDouble(), (photo.y + photo.height - 1).toDouble()), Scalar(v, v * 0.97, v * 0.92))
        }
        val stripe = rgb.submat(Rect(140, 168, 760, 40))
        Core.multiply(stripe, Scalar(1.0, 1.0, 0.55), stripe)

        // A phone camera's grain.
        val grain = Mat(rgb.size(), CvType.CV_16SC3)
        Core.randn(grain, 0.0, 3.0)
        val noisy = Mat()
        rgb.convertTo(noisy, CvType.CV_16SC3)
        Core.add(noisy, grain, noisy)
        noisy.convertTo(rgb, CvType.CV_8UC3)

        val mask = Cleanup.mask(rgb, PageEdits(removeShowThrough = true))!!
        // Where the back shows through clear paper on the front.
        val truth = Mat()
        Core.inRange(back, Scalar(0.0), Scalar(215.0), truth)
        val busy = Mat()
        Core.min(front, pencil, busy)
        Imgproc.threshold(busy, busy, 240.0, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_and(truth, busy, truth)
        truth.submat(photo).setTo(Scalar(0.0))
        val found = Mat(); Core.bitwise_and(mask, truth, found)
        val share = Core.countNonZero(found).toDouble() / Core.countNonZero(truth)
        assertTrue("found only ${share * 100}% of the show-through", share > 0.8)

        val photoShare = Core.countNonZero(mask.submat(photo)).toDouble() / photo.area()
        assertTrue("marked ${photoShare * 100}% of the photo", photoShare < 0.02)
        val pencilInk = Mat(); Imgproc.threshold(pencil, pencilInk, 128.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val pencilHit = Mat(); Core.bitwise_and(mask, pencilInk, pencilHit)
        assertTrue("marked ${Core.countNonZero(pencilHit)} pencil pixels", Core.countNonZero(pencilHit) < Core.countNonZero(pencilInk) * 0.02)
        val stripeHit = Core.countNonZero(mask.submat(Rect(150, 175, 740, 26)))
        assertTrue("marked $stripeHit highlighter pixels", stripeHit < 740 * 26 * 0.02)
    }
}
