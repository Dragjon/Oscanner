package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.IdBackground
import dev.olaf.oscanner.core.model.IdPhotoSize
import org.junit.Assert.assertEquals
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

class IdPhotoTest {

    @Test
    fun framesTheHeadByEachSizesRules() {
        val face = IdPhoto.Face(eyesX = 600.0, eyesY = 500.0, eyesDistance = 100.0)
        for (size in IdPhotoSize.entries) {
            val box = IdPhoto.frame(face, size)
            assertEquals("${size.name}: shape", size.widthMm / size.heightMm.toDouble(), box.width / box.height, 1e-6)
            assertEquals("${size.name}: centred on the eyes", 600.0, (box.left + box.right) / 2, 1e-6)
            // Chin to crown about 3.9 eye distances (as FaceDetector measures them), the eyes halfway down.
            val head = 390.0
            val crown = 500.0 - 0.5 * head
            assertEquals("${size.name}: head share", size.head.toDouble(), head / box.height, 1e-6)
            assertEquals("${size.name}: room above", size.top.toDouble(), (crown - box.top) / box.height, 1e-6)
        }
        // A 35 × 45 mm photo's head is 32 to 36 mm, as the rules ask.
        val box = IdPhoto.frame(face, IdPhotoSize.Mm35x45)
        assertTrue(390.0 / box.height * 45 in 32.0..36.0)
    }

    /** A person in front of a mottled blue-green wall: hair, a face, a neck and a shirt. */
    private fun portrait(): Pair<Mat, IdPhoto.Face> {
        val wall = Mat(1600, 1200, CvType.CV_8UC3, Scalar(120.0, 160.0, 150.0))
        val noise = Mat(wall.size(), CvType.CV_8UC3)
        Core.randn(noise, 0.0, 6.0)
        Core.add(wall, noise, wall)
        val skin = Scalar(225.0, 180.0, 150.0)
        Imgproc.rectangle(wall, Point(300.0, 1150.0), Point(900.0, 1600.0), Scalar(40.0, 50.0, 110.0), -1) // shirt
        Imgproc.rectangle(wall, Point(530.0, 950.0), Point(670.0, 1160.0), skin, -1) // neck
        Imgproc.ellipse(wall, Point(600.0, 620.0), Size(210.0, 270.0), 0.0, 0.0, 360.0, Scalar(60.0, 40.0, 30.0), -1) // hair
        Imgproc.ellipse(wall, Point(600.0, 700.0), Size(180.0, 250.0), 0.0, 0.0, 360.0, skin, -1) // face
        Imgproc.circle(wall, Point(525.0, 650.0), 14, Scalar(30.0, 30.0, 30.0), -1)
        Imgproc.circle(wall, Point(675.0, 650.0), 14, Scalar(30.0, 30.0, 30.0), -1)
        return wall to IdPhoto.Face(600.0, 650.0, 150.0)
    }

    private fun mean(img: Mat, r: Rect) = Core.mean(img.submat(r)).`val`

    @Test
    fun replacesTheWallAndKeepsThePerson() {
        nu.pattern.OpenCV.loadLocally()
        val (photo, face) = portrait()
        val out = IdPhoto.make(photo, face, IdPhotoSize.Mm35x45, IdBackground.White)
        // 35 × 45 mm at 300 dpi.
        assertEquals(413, out.cols())
        assertEquals(531, out.rows())
        // Top corners were wall: now white. The middle of the face is still skin.
        val corner = mean(out, Rect(5, 5, 40, 40))
        assertTrue("wall left: ${corner.toList()}", corner.take(3).all { it > 240 })
        val cheek = mean(out, Rect(190, 300, 30, 30))
        assertTrue("face lost: ${cheek.toList()}", cheek[0] in 205.0..245.0 && cheek[2] in 130.0..170.0)

        // Kept as taken, the wall stays.
        val kept = IdPhoto.make(photo, face, IdPhotoSize.Mm35x45, IdBackground.Keep)
        assertTrue(mean(kept, Rect(5, 5, 40, 40))[1] in 140.0..180.0)

        // Six 35 × 45 mm photos fit a 4 × 6 in sheet.
        val sheet = IdPhoto.sheet(out)
        assertEquals(1200, sheet.cols())
        assertEquals(1800, sheet.rows())
        assertEquals(6, countPhotos(sheet))
    }

    /** Photos on the sheet, counted by the skin-colored faces in them. */
    private fun countPhotos(sheet: Mat): Int {
        val skin = Mat()
        Core.inRange(sheet, Scalar(205.0, 160.0, 130.0), Scalar(245.0, 200.0, 170.0), skin)
        Imgproc.morphologyEx(skin, skin, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)))
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(skin, labels, stats, centroids)
        return (1 until n).count { stats.get(it, Imgproc.CC_STAT_AREA)[0] > 5000 }
    }
}
