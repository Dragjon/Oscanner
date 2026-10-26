package dev.olaf.oscanner.core.imaging

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * A real print torn at a corner over water ("Biloxi Light House, Miss.", Detroit Photographic
 * Co., 1901, public domain; aged and torn by `tools/welcome-assets/scenes2.py`). Matched from
 * anywhere, the gap took in a piece of the houses and brick from up the bank, where the same
 * fence runs; it should be water, like what's beside it.
 */
class TornPrintTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = nu.pattern.OpenCV.loadLocally()
    }

    @Test
    fun aCornerTornOffOverWaterIsFilledWithWater() {
        val file = File(javaClass.getResource("/torn-print.jpg")!!.toURI())
        val photo = Mat().also { Imgproc.cvtColor(Imgcodecs.imread(file.path), it, Imgproc.COLOR_BGR2RGB) }
        val desk = listOf(Scalar(112.0, 82.0, 58.0))
        val gap = OldPhoto.tornMask(photo, desk)!!
        OldPhoto.mendTorn(photo, desk)
        // The water beside the gap, along the same rows.
        val w = photo.cols()
        val h = photo.rows()
        val lab = Mat()
        photo.convertTo(lab, CvType.CV_32FC3, 1.0 / 255)
        Imgproc.GaussianBlur(lab, lab, Size(5.0, 5.0), 0.0)
        Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2Lab)
        val water = Core.mean(lab.submat(Rect((w * 0.25).toInt(), (h * 0.83).toInt(), (w * 0.2).toInt(), (h * 0.07).toInt()))).`val`
        val gapPx = ByteArray(w * h).also { gap.get(0, 0, it) }
        val labPx = FloatArray(w * h * 3).also { lab.get(0, 0, it) }
        var inGap = 0
        var like = 0
        for (i in gapPx.indices) {
            if (gapPx[i] == 0.toByte()) continue
            inGap++
            val dl = labPx[i * 3] - water[0]
            val da = labPx[i * 3 + 1] - water[1]
            val db = labPx[i * 3 + 2] - water[2]
            if (dl * dl + da * da + db * db < 8.0 * 8.0) like++
        }
        val share = like.toDouble() / inGap
        assertTrue("only $share of the gap looks like the water beside it", share > 0.85)
    }
}
