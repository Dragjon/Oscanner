package dev.olaf.oscanner.core.imaging

import android.graphics.Bitmap
import android.media.FaceDetector
import dev.olaf.oscanner.core.model.IdBackground
import dev.olaf.oscanner.core.model.IdPhotoSize
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Passport and ID photos from a portrait: the face is found, the photo cut around it to the
 * size's rules, the background replaced with a plain color if asked, and copies laid out on a
 * 4 × 6 in sheet for a photo printer. All at 300 dpi.
 */
object IdPhoto {

    /** A face in pixels: the point between the eyes, and how far apart they are. */
    data class Face(val eyesX: Double, val eyesY: Double, val eyesDistance: Double)

    /** A rectangle in pixels; it may run past the photo, which is then padded with background. */
    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /**
     * The ID photo of [size] on [background] from [photo], and a sheet of copies, both as JPEGs
     * marked 300 dpi; null when there's no face in it.
     */
    fun fromPhoto(photo: Bitmap, size: IdPhotoSize, background: IdBackground): Pair<ByteArray, ByteArray>? {
        PageRenderer.ensureLoaded()
        val face = findFace(photo) ?: return null
        val rgba = Mat()
        Utils.bitmapToMat(photo, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        val single = make(rgb, face, size, background)
        rgb.release()
        val copies = sheet(single)
        return jpeg(single).also { single.release() } to jpeg(copies).also { copies.release() }
    }

    /** [rgb] as a JPEG whose header says 300 dots per inch, so it prints at its true size. */
    private fun jpeg(rgb: Mat): ByteArray {
        val bitmap = toBitmap(rgb)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        bitmap.recycle()
        // The JFIF header right after the start marker: units at 13 (1 = per inch), then x and y density.
        val jfif = bytes.size > 18 && bytes[2] == 0xFF.toByte() && bytes[3] == 0xE0.toByte() &&
            String(bytes, 6, 4, Charsets.US_ASCII) == "JFIF"
        if (jfif) {
            bytes[13] = 1
            bytes[14] = (DPI.toInt() shr 8).toByte()
            bytes[15] = DPI.toInt().toByte()
            bytes[16] = (DPI.toInt() shr 8).toByte()
            bytes[17] = DPI.toInt().toByte()
        }
        return bytes
    }

    /** The biggest face in [photo], or null. */
    fun findFace(photo: Bitmap): Face? {
        // FaceDetector wants RGB_565 and an even width, and is quick on a few hundred pixels.
        val scale = min(1.0, FACE_SIDE / max(photo.width, photo.height))
        val w = ((photo.width * scale).toInt() / 2) * 2
        val h = (photo.height * scale).toInt()
        val small = Bitmap.createScaledBitmap(photo, w, h, true).copy(Bitmap.Config.RGB_565, false)
        val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val count = FaceDetector(w, h, MAX_FACES).findFaces(small, faces)
        // The likeliest face, and one with skin below its eyes: a pattern in the background
        // (a model rocket, a picture on the wall) can pass for a face nearly as surely.
        val best = faces.take(count).filterNotNull()
            .filter { it.confidence() >= MIN_CONFIDENCE }
            .maxByOrNull { it.confidence() + skinBelowEyes(small, it) }
        small.recycle()
        best ?: return null
        val mid = android.graphics.PointF().also(best::getMidPoint)
        return Face(mid.x / scale, mid.y / scale, best.eyesDistance() / scale)
    }

    /** The share of skin-colored pixels (YCrCb) in a patch of [photo] below the eyes of [face]. */
    private fun skinBelowEyes(photo: Bitmap, face: FaceDetector.Face): Float {
        val mid = android.graphics.PointF().also(face::getMidPoint)
        val d = face.eyesDistance()
        val x0 = (mid.x - d * 0.5f).toInt().coerceIn(0, photo.width - 1)
        val y0 = (mid.y + d * 0.3f).toInt().coerceIn(0, photo.height - 1)
        val x1 = (mid.x + d * 0.5f).toInt().coerceIn(x0 + 1, photo.width)
        val y1 = (mid.y + d * 1.1f).toInt().coerceIn(y0 + 1, photo.height)
        val pixels = IntArray((x1 - x0) * (y1 - y0))
        photo.getPixels(pixels, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0)
        val skin = pixels.count { p ->
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val cr = 128 + 0.5 * r - 0.4187 * g - 0.0813 * b
            val cb = 128 - 0.1687 * r - 0.3313 * g + 0.5 * b
            cr in 133.0..173.0 && cb in 77.0..127.0
        }
        return skin.toFloat() / pixels.size
    }

    /**
     * Where to cut the photo for [size], around [face]. A head, chin to crown, is about
     * [HEAD_PER_EYES] times the distance between the eyes, which sit [EYES_DOWN] of the way down it.
     */
    fun frame(face: Face, size: IdPhotoSize): Box {
        val head = HEAD_PER_EYES * face.eyesDistance
        val crown = face.eyesY - EYES_DOWN * head
        val height = head / size.head
        val width = height * size.widthMm / size.heightMm
        val top = crown - size.top * height
        val left = face.eyesX - width / 2
        return Box(left, top, left + width, top + height)
    }

    /**
     * The ID photo itself, [size] at 300 dpi, cut from [photo] (8-bit RGB) around [face], on
     * [background]. Parts of the frame past the photo's edge are filled with the background.
     */
    fun make(photo: Mat, face: Face, size: IdPhotoSize, background: IdBackground): Mat {
        val box = frame(face, size)
        val person = background.rgb?.let { person(photo, face) }
        val fill = background.rgb?.let { colour(it) }
            ?: Core.mean(photo.submat(Rect(0, 0, photo.cols(), max(1, photo.rows() / 20))))
        // The person composited on the background, over the photo's full area.
        val scene = photo.clone()
        if (person != null) {
            val plain = Mat(photo.size(), photo.type(), fill)
            val alpha = Mat()
            person.convertTo(alpha, CvType.CV_32F, 1.0 / 255)
            Imgproc.cvtColor(alpha, alpha, Imgproc.COLOR_GRAY2RGB)
            val f = Mat()
            val b = Mat()
            photo.convertTo(f, CvType.CV_32FC3)
            plain.convertTo(b, CvType.CV_32FC3)
            Core.subtract(f, b, f)
            Core.multiply(f, alpha, f)
            Core.add(f, b, f)
            f.convertTo(scene, CvType.CV_8UC3)
            listOf(plain, alpha, f, b, person).forEach(Mat::release)
        }
        // Cut the frame, padding outside the photo.
        val outW = (size.widthMm / MM_PER_INCH * DPI).roundToInt()
        val outH = (size.heightMm / MM_PER_INCH * DPI).roundToInt()
        val padX = max(0.0, max(-box.left, box.right - photo.cols())).toInt() + 1
        val padY = max(0.0, max(-box.top, box.bottom - photo.rows())).toInt() + 1
        val padded = Mat()
        Core.copyMakeBorder(scene, padded, padY, padY, padX, padX, Core.BORDER_CONSTANT, fill)
        scene.release()
        val cut = padded.submat(
            Rect((box.left + padX).roundToInt(), (box.top + padY).roundToInt(), box.width.roundToInt(), box.height.roundToInt()),
        )
        val out = Mat()
        Imgproc.resize(cut, out, Size(outW.toDouble(), outH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        listOf(cut, padded).forEach(Mat::release)
        return out
    }

    /**
     * [photo] (an ID photo, 300 dpi) repeated on a 4 × 6 in sheet, upright, as many as fit with
     * a little room between them, centered, with thin gray lines to cut along.
     */
    fun sheet(photo: Mat): Mat {
        val w = (SHEET_SHORT_IN * DPI).toInt()
        val h = (SHEET_LONG_IN * DPI).toInt()
        val gap = (GAP_MM / MM_PER_INCH * DPI).roundToInt()
        val columns = max(1, (w + gap) / (photo.cols() + gap))
        val rows = max(1, (h + gap) / (photo.rows() + gap))
        val sheet = Mat(h, w, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        val usedW = columns * photo.cols() + (columns - 1) * gap
        val usedH = rows * photo.rows() + (rows - 1) * gap
        val x0 = (w - usedW) / 2
        val y0 = (h - usedH) / 2
        val line = Scalar(200.0, 200.0, 200.0)
        for (r in 0 until rows) for (c in 0 until columns) {
            val x = x0 + c * (photo.cols() + gap)
            val y = y0 + r * (photo.rows() + gap)
            photo.copyTo(sheet.submat(Rect(x, y, photo.cols(), photo.rows())))
            Imgproc.rectangle(sheet, Point(x - 1.0, y - 1.0), Point((x + photo.cols()).toDouble(), (y + photo.rows()).toDouble()), line, 1)
        }
        return sheet
    }

    /**
     * Where the person is, 0..255 (feathered at the edge), in [photo]'s pixels: GrabCut on a small
     * copy, told the face is surely the person, the head and shoulders likely, and the photo's
     * sides and top corners surely background.
     */
    internal fun person(photo: Mat, face: Face): Mat {
        val scale = min(1.0, CUT_SIDE / max(photo.cols(), photo.rows()))
        val small = Mat()
        Imgproc.resize(photo, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val w = small.cols()
        val h = small.rows()
        val head = HEAD_PER_EYES * face.eyesDistance * scale
        val cx = face.eyesX * scale
        val crown = face.eyesY * scale - EYES_DOWN * head
        val chin = crown + head
        val seeds = Mat(h, w, CvType.CV_8UC1, Scalar(Imgproc.GC_PR_BGD.toDouble()))
        // Likely person: the head with its hair, the neck, and the shoulders down to the bottom.
        Imgproc.ellipse(seeds, Point(cx, crown + head * 0.5), Size(head * 0.48, head * 0.58), 0.0, 0.0, 360.0, Scalar(Imgproc.GC_PR_FGD.toDouble()), -1)
        Imgproc.rectangle(seeds, Point(cx - head * 0.25, chin - head * 0.1), Point(cx + head * 0.25, chin + head * 0.3), Scalar(Imgproc.GC_PR_FGD.toDouble()), -1)
        Imgproc.rectangle(seeds, Point(cx - head * 1.1, chin + head * 0.25), Point(cx + head * 1.1, h.toDouble()), Scalar(Imgproc.GC_PR_FGD.toDouble()), -1)
        // Surely person: the middle of the face.
        Imgproc.ellipse(seeds, Point(cx, crown + head * 0.55), Size(head * 0.22, head * 0.3), 0.0, 0.0, 360.0, Scalar(Imgproc.GC_FGD.toDouble()), -1)
        // Surely background: a strip down each side and across the top, clear of the person.
        val strip = max(2, (w * 0.03).toInt())
        val sure = Scalar(Imgproc.GC_BGD.toDouble())
        if (cx - head * 1.3 > strip) Imgproc.rectangle(seeds, Point(0.0, 0.0), Point(strip.toDouble(), h.toDouble()), sure, -1)
        if (cx + head * 1.3 < w - strip) Imgproc.rectangle(seeds, Point((w - strip).toDouble(), 0.0), Point(w.toDouble(), h.toDouble()), sure, -1)
        if (crown - head * 0.15 > strip) Imgproc.rectangle(seeds, Point(0.0, 0.0), Point(w.toDouble(), strip.toDouble()), sure, -1)
        val bgModel = Mat()
        val fgModel = Mat()
        Imgproc.grabCut(small, seeds, Rect(), bgModel, fgModel, CUT_ROUNDS, Imgproc.GC_INIT_WITH_MASK)
        listOf(small, bgModel, fgModel).forEach(Mat::release)
        // Person: sure or likely foreground.
        val fg = Mat()
        Core.compare(seeds, Scalar(Imgproc.GC_PR_FGD.toDouble()), fg, Core.CMP_EQ)
        val sureFg = Mat()
        Core.compare(seeds, Scalar(Imgproc.GC_FGD.toDouble()), sureFg, Core.CMP_EQ)
        Core.bitwise_or(fg, sureFg, fg)
        listOf(seeds, sureFg).forEach(Mat::release)
        Imgproc.morphologyEx(fg, fg, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)))
        Imgproc.morphologyEx(fg, fg, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0)))
        // Only the piece with the face in it.
        val labels = Mat()
        Imgproc.connectedComponents(fg, labels)
        val at = labels.get(
            (crown + head * 0.55).toInt().coerceIn(0, h - 1),
            cx.toInt().coerceIn(0, w - 1),
        )[0]
        if (at > 0) Core.compare(labels, Scalar(at), fg, Core.CMP_EQ)
        labels.release()
        val full = Mat()
        Imgproc.resize(fg, full, photo.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        fg.release()
        val soft = max(1.0, max(photo.cols(), photo.rows()) / 600.0)
        Imgproc.GaussianBlur(full, full, Size(0.0, 0.0), soft)
        return full
    }

    private fun colour(rgb: Int) = Scalar(((rgb shr 16) and 0xFF).toDouble(), ((rgb shr 8) and 0xFF).toDouble(), (rgb and 0xFF).toDouble())

    private fun toBitmap(rgb: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
        val bitmap = Bitmap.createBitmap(rgb.cols(), rgb.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }

    /**
     * Chin to crown is about this many times the distance between the eyes as FaceDetector
     * measures it (a little short of the true one)...
     */
    private const val HEAD_PER_EYES = 3.9
    /** ...and the eyes about halfway down from the crown. */
    private const val EYES_DOWN = 0.5
    private const val FACE_SIDE = 640.0
    private const val MAX_FACES = 4
    private const val MIN_CONFIDENCE = 0.4f
    private const val CUT_SIDE = 480.0
    private const val CUT_ROUNDS = 4
    private const val DPI = 300.0
    private const val MM_PER_INCH = 25.4f
    private const val SHEET_SHORT_IN = 4.0
    private const val SHEET_LONG_IN = 6.0
    private const val GAP_MM = 2f
}
