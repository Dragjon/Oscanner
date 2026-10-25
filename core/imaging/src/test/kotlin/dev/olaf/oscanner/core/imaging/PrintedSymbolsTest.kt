package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Area
import dev.olaf.oscanner.core.model.PageEdits
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * A worksheet's printed signs between its printed words ("1/2 + 1/4 =") are too small for the
 * text reader to count as words, and too few pixels to judge by their ink; they're print all the
 * same, and stay when the pen answers go.
 */
class PrintedSymbolsTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun load() = nu.pattern.OpenCV.loadLocally()
    }

    @Test
    fun signsBetweenPrintedWordsStay() {
        val w = 1240
        val h = 1754
        val page = Mat(h, w, CvType.CV_8UC3, Scalar(250.0, 250.0, 247.0))
        val printed = ArrayList<Area>()
        val plusBoxes = ArrayList<Rect>()
        val answerBoxes = ArrayList<Rect>()
        val ink = Scalar(25.0, 25.0, 25.0)
        val pen = Scalar(28.0, 52.0, 160.0)
        fun word(text: String, x: Int, y: Int, count: Boolean): Rect {
            val size = Imgproc.getTextSize(text, Imgproc.FONT_HERSHEY_SIMPLEX, 1.3, 3, IntArray(1))
            Imgproc.putText(page, text, Point(x.toDouble(), y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 1.3, ink, 3)
            val box = Rect(x, y - size.height.toInt(), size.width.toInt(), size.height.toInt() + 8)
            if (count) printed += Area(box.x.toFloat() / w, box.y.toFloat() / h, (box.x + box.width).toFloat() / w, (box.y + box.height).toFloat() / h)
            return box
        }
        word("Fractions practice", 110, 140, true)
        for (i in 0 until 6) {
            val y = 300 + i * 200
            var x = 110
            x += word("${i + 1}.", x, y, true).width + 30
            x += word("1/2", x, y, true).width + 30
            // The reader didn't take the plus sign for a word.
            plusBoxes += word("+", x, y, false)
            x += plusBoxes.last().width + 30
            x += word("1/4", x, y, true).width + 30
            val equals = word("=", x, y, true)
            if (i == 2) {
                // A one-figure answer close after the equals sign: print on one side only, so pen.
                Imgproc.putText(page, "8", Point(equals.x + equals.width + 24.0, y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 1.3, ink, 3)
                answerBoxes += Rect(equals.x + equals.width + 20, y - 34, 34, 42)
            }
            // The pen answer after the equals sign.
            Imgproc.putText(page, "3/4", Point(760.0, y.toDouble()), Imgproc.FONT_HERSHEY_SCRIPT_SIMPLEX, 1.8, pen, 3)
            answerBoxes += Rect(760, y - 45, 110, 55)
            Imgproc.line(page, Point(110.0, y + 110.0), Point(w - 110.0, y + 110.0), Scalar(200.0, 200.0, 200.0), 2)
        }
        // Print found by the reader's confident words, and print found by its look (each mark exact).
        for (byLook in listOf(false, true)) {
            val mask = Cleanup.mask(page, PageEdits(removePenInk = true, printed = printed, printByLook = byLook))!!
            plusBoxes.forEachIndexed { i, b ->
                val marked = Core.countNonZero(mask.submat(b))
                assertTrue("by look $byLook: plus sign $i marked for removal ($marked px)", marked == 0)
            }
            answerBoxes.forEachIndexed { i, b ->
                assertTrue("by look $byLook: pen answer $i stayed", Core.countNonZero(mask.submat(b)) > 150)
            }
        }
    }
}
