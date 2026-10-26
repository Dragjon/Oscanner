package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.InkMark
import dev.olaf.oscanner.core.model.PageEdits
import dev.olaf.oscanner.core.model.PageFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.random.Random

class TornPhotoTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = nu.pattern.OpenCV.loadLocally()

        private const val W = 1200
        private const val H = 900
        private val DESK = Scalar(112.0, 82.0, 58.0)
    }

    /** A landscape: blue sky fading paler toward the horizon over speckled green grass. */
    private fun landscape(sky: (Double) -> Scalar = { t -> Scalar(90 + 80 * t, 140 + 60 * t, 220.0) }): Mat {
        val m = Mat(H, W, CvType.CV_8UC3)
        for (y in 0 until H / 2) Imgproc.line(m, Point(0.0, y.toDouble()), Point(W.toDouble(), y.toDouble()), sky(y / (H / 2.0)))
        m.submat(H / 2, H, 0, W).setTo(Scalar(70.0, 130.0, 50.0))
        val r = Random(3)
        repeat(30000) {
            val x = r.nextInt(W)
            val y = H / 2 + r.nextInt(H / 2)
            val g = r.nextInt(-40, 40)
            Imgproc.line(m, Point(x.toDouble(), y.toDouble()), Point(x + r.nextInt(-2, 3).toDouble(), y - r.nextInt(2, 8).toDouble()), Scalar(70.0 + g / 2, 130.0 + g, 50.0 + g / 3))
        }
        return m
    }

    /** The bottom-right corner torn away, ragged, showing desk inside a white rim. */
    private fun tear(photo: Mat): Mat {
        val r = Random(5)
        val edge = (0..10).map { i ->
            val t = i / 10.0
            // From the bottom edge, 330 px in from the corner, up to the right edge 260 px above it.
            Point(W - 330 + 330 * t + r.nextInt(-12, 12), H - 260 * t + r.nextInt(-12, 12))
        }
        val rim = MatOfPoint(*(edge + listOf(Point(W.toDouble(), H.toDouble()))).toTypedArray())
        Imgproc.fillPoly(photo, listOf(rim), Scalar(245.0, 243.0, 238.0))
        val gap = MatOfPoint(*(edge.map { Point(it.x + 9, it.y + 9) } + listOf(Point(W.toDouble(), H.toDouble()))).toTypedArray())
        Imgproc.fillPoly(photo, listOf(gap), DESK)
        val truth = Mat.zeros(H, W, CvType.CV_8UC1)
        Imgproc.fillPoly(truth, listOf(rim), Scalar(255.0))
        return truth
    }

    @Test
    fun aTornCornerIsFoundAndFilledWithGrass() {
        val photo = landscape()
        val truth = tear(photo)
        val mask = OldPhoto.tornMask(photo, DESK)!!
        val both = Mat()
        val either = Mat()
        Core.bitwise_and(mask, truth, both)
        Core.bitwise_or(mask, truth, either)
        val overlap = Core.countNonZero(both).toDouble() / Core.countNonZero(either)
        assertTrue("found $overlap of the tear", overlap > 0.8)

        OldPhoto.mendTorn(photo, DESK)
        // What's in the gap now looks like the grass beside it: its color, and its texture.
        val filled = MatOfDouble()
        val filledSpread = MatOfDouble()
        Core.meanStdDev(photo.submat(Rect(W - 120, H - 80, 100, 60)), filled, filledSpread)
        val grass = MatOfDouble()
        val grassSpread = MatOfDouble()
        Core.meanStdDev(photo.submat(Rect(W - 600, H - 80, 100, 60)), grass, grassSpread)
        for (c in 0..2) assertEquals("channel $c", grass.toArray()[c], filled.toArray()[c], 12.0)
        assertTrue("texture ${filledSpread.toArray()[1]} vs ${grassSpread.toArray()[1]}", filledSpread.toArray()[1] > 0.5 * grassSpread.toArray()[1])
    }

    @Test
    fun aBiteOutOfTheEdgeCarriesTheHorizonOn() {
        val photo = landscape()
        // A half-round bite out of the right edge, across the horizon.
        Imgproc.circle(photo, Point(W.toDouble(), H / 2.0), 150, Scalar(245.0, 243.0, 238.0), -1)
        Imgproc.circle(photo, Point(W.toDouble(), H / 2.0), 140, DESK, -1)
        OldPhoto.mendTorn(photo, DESK)
        // Above the horizon it's sky again (blue), below it grass (green).
        val above = Core.mean(photo.submat(Rect(W - 60, H / 2 - 100, 50, 60))).`val`
        val below = Core.mean(photo.submat(Rect(W - 60, H / 2 + 40, 50, 60))).`val`
        assertTrue("sky ${above.toList()}", above[2] > above[1] && above[2] > 180)
        assertTrue("grass ${below.toList()}", below[1] > below[0] + 30 && below[1] > below[2] + 30)
    }

    @Test
    fun aStainPaintedOverOnAPhotoIsFilledWithPicture() {
        val photo = landscape()
        // A big coffee stain on the grass, brushed over in Clean up.
        Imgproc.circle(photo, Point(700.0, 700.0), 90, Scalar(120.0, 85.0, 50.0), -1)
        val stroke = InkMark(listOf(Corner(530f / W, 700f / H), Corner(870f / W, 700f / H)), 0, 200f / W)
        Cleanup.apply(photo, PageEdits(filter = PageFilter.Photo, erase = listOf(stroke)))
        // Grass again, with grass's texture: not a smooth smear of its color.
        val mean = MatOfDouble()
        val spread = MatOfDouble()
        Core.meanStdDev(photo.submat(Rect(650, 650, 100, 100)), mean, spread)
        val grass = MatOfDouble()
        val grassSpread = MatOfDouble()
        Core.meanStdDev(photo.submat(Rect(200, 650, 100, 100)), grass, grassSpread)
        for (c in 0..2) assertEquals("channel $c", grass.toArray()[c], mean.toArray()[c], 12.0)
        assertTrue("texture ${spread.toArray()[1]} vs ${grassSpread.toArray()[1]}", spread.toArray()[1] > 0.5 * grassSpread.toArray()[1])
    }

    @Test
    fun aPaleSkyOnAPaleDeskStays() {
        // Overcast sky fading in from white at the top, lying on a white scanner lid.
        val photo = landscape { t -> Scalar(240 - 70 * t, 240 - 50 * t, 238 - 20 * t) }
        assertNull(OldPhoto.tornMask(photo, Scalar(240.0, 240.0, 238.0)))
    }
}
