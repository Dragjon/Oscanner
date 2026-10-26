package dev.olaf.oscanner.core.imaging

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Random
import kotlin.math.hypot
import kotlin.math.max

class PageStitcherTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = nu.pattern.OpenCV.loadLocally()

        const val W = 1800
        const val H = 2600
        const val SHOT_W = 1600
        const val SHOT_H = 1000
    }

    /** A poster: lines of made-up words, some boxes and rings in color. */
    private fun poster(seed: Long): Mat {
        val rng = Random(seed)
        val page = Mat(H, W, CvType.CV_8UC3, Scalar(245.0, 243.0, 238.0))
        for (y in 80 until H - 40 step 56) {
            var x = 60
            while (x < W - 200) {
                val word = String(CharArray(3 + rng.nextInt(7)) { ('a' + rng.nextInt(26)) })
                Imgproc.putText(page, word, Point(x.toDouble(), y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(30.0, 30.0, 30.0), 2)
                x += word.length * 22 + 30
            }
        }
        repeat(25) {
            val c = Scalar(rng.nextInt(200).toDouble(), rng.nextInt(200).toDouble(), rng.nextInt(200).toDouble())
            val x = rng.nextInt(W - 200).toDouble()
            val y = rng.nextInt(H - 200).toDouble()
            if (rng.nextBoolean()) Imgproc.rectangle(page, Point(x, y), Point(x + 40 + rng.nextInt(150), y + 40 + rng.nextInt(150)), c, 4)
            else Imgproc.circle(page, Point(x, y), 20 + rng.nextInt(80), c, 5)
        }
        return page
    }

    /** From a shot's pixels to the poster's: the shot looks at [top]..[top]+1100 of it, a little askew. */
    private fun view(top: Double, rng: Random): Mat {
        fun j() = (rng.nextDouble() - 0.5) * 120
        val src = MatOfPoint2f(Point(0.0, 0.0), Point(SHOT_W.toDouble(), 0.0), Point(SHOT_W.toDouble(), SHOT_H.toDouble()), Point(0.0, SHOT_H.toDouble()))
        val dst = MatOfPoint2f(Point(j(), top + j()), Point(W + j(), top + j()), Point(W + j(), top + 1100 + j()), Point(j(), top + 1100 + j()))
        return Imgproc.getPerspectiveTransform(src, dst)
    }

    private fun shoot(poster: Mat, toPoster: Mat, light: Double, rng: Random): Mat {
        val shot = Mat()
        Imgproc.warpPerspective(poster, shot, toPoster.inv(), Size(SHOT_W.toDouble(), SHOT_H.toDouble()), Imgproc.INTER_AREA, Core.BORDER_CONSTANT, Scalar(110.0, 90.0, 70.0))
        Core.multiply(shot, Scalar(light, light, light), shot)
        val noise = Mat(shot.size(), shot.type())
        Core.randn(noise, 0.0, 4.0 + rng.nextInt(3))
        Core.add(shot, noise, shot)
        return shot
    }

    private fun sized(shot: Mat, side: Int): Mat {
        val out = Mat()
        val f = side.toDouble() / max(shot.cols(), shot.rows())
        Imgproc.resize(shot, out, Size(), f, f, Imgproc.INTER_AREA)
        return out
    }

    @Test
    fun overlappingShotsLandWhereTheyBelong() {
        val rng = Random(7)
        val poster = poster(3)
        val views = listOf(0.0, 750.0, 1500.0).map { view(it, rng) }
        val shots = views.mapIndexed { i, v -> shoot(poster, v, 0.85 + 0.1 * i, rng) }
        val placement = PageStitcher.place(3) { i, side -> sized(shots[i], side) }
        assertNotNull(placement)
        val s = PageStitcher.MATCH_SIDE.toDouble() / SHOT_W
        val m = placement!!.middle
        for (i in 0 until 3) for (p in listOf(Point(0.0, 0.0), Point(SHOT_W.toDouble(), 0.0), Point(SHOT_W.toDouble(), SHOT_H.toDouble()), Point(0.0, SHOT_H.toDouble()))) {
            val predicted = PageStitcher.map(placement.transforms[i], p.x * s, p.y * s)
            val onPoster = PageStitcher.map(views[i], p.x, p.y)
            val truth = PageStitcher.map(views[m].inv(), onPoster.x, onPoster.y).let { Point(it.x * s, it.y * s) }
            val error = hypot(predicted.x - truth.x, predicted.y - truth.y)
            assertTrue("shot $i corner $p off by $error px", error < 3.0)
        }
        placement.release()
    }

    @Test
    fun theJoinedPictureCoversTheWholePoster() {
        val rng = Random(11)
        val poster = poster(5)
        val views = listOf(0.0, 750.0, 1500.0).map { view(it, rng) }
        val shots = views.mapIndexed { i, v -> shoot(poster, v, 1.0 - 0.1 * i, rng) }
        val joined = PageStitcher.stitch(3, side = 1600) { i, side -> sized(shots[i], side) }
        assertNotNull(joined)
        System.getenv("STITCH_OUT")?.let { org.opencv.imgcodecs.Imgcodecs.imwrite(it, joined) }
        // The poster is 2600 tall to 1800 wide; each shot shows 1100 of it in 1000 pixels.
        val ratio = joined!!.rows().toDouble() / joined.cols()
        assertTrue("joined ${joined.cols()}×${joined.rows()}", ratio in 1.2..1.65)
        assertTrue("joined ${joined.rows()} rows", joined.rows() in 2000..2700)
    }

    @Test
    fun shotsThatDoNotOverlapAreNotJoined() {
        val rng = Random(13)
        val a = poster(1)
        val b = poster(2)
        val shots = listOf(shoot(a, view(0.0, rng), 1.0, rng), shoot(b, view(1500.0, rng), 1.0, rng))
        assertNull(PageStitcher.place(2) { i, side -> sized(shots[i], side) })
    }
}
