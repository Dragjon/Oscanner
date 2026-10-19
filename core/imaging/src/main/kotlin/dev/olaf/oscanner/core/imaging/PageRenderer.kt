package dev.olaf.oscanner.core.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import dev.olaf.oscanner.core.model.PageEdits
import dev.olaf.oscanner.core.model.PageFilter
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Turns an original photo plus its [PageEdits] into the page people see. Call off the main thread. */
object PageRenderer {

    /** Long side of a full render. Sharp enough to print a page at ~300 dpi on A5, ~240 on A4. */
    const val RENDER_SIDE = 2800
    const val THUMB_SIDE = 360
    /** Roughly 300 dpi on an A4 page: what Tesseract reads best. */
    const val OCR_SIDE = 3400

    @Volatile private var loaded = false

    /** Loads OpenCV's native code once. Cheap after the first call. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
                loaded = true
            }
        }
    }

    /** Decodes [path] turned upright per its EXIF tag, with the long side near [maxSide] (never under it unless the photo is). */
    fun loadUpright(path: String, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Can't decode $path")
        val degrees = runCatching { ExifInterface(path).rotationDegrees }.getOrDefault(0)
        val scale = min(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
        if (degrees == 0 && scale == 1f) return decoded
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it != decoded) decoded.recycle()
        }
    }

    /** Crops, straightens, rotates and filters [source] (an upright photo). */
    fun render(source: Bitmap, edits: PageEdits, maxSide: Int = RENDER_SIDE): Bitmap {
        ensureLoaded()
        val warped = warp(source, edits, maxSide)
        val filtered = Filters.apply(warped, edits.filter)
        warped.release()
        val adjusted = Filters.adjust(filtered, edits.filter, edits.brightness, edits.contrast, edits.detail)
        return toBitmap(adjusted).also { adjusted.release() }
    }

    /**
     * The page as Tesseract should see it: same crop, straightening and rotation as [render], so
     * word boxes line up with the page people see, but always in clean black and white (Ink).
     */
    fun renderForOcr(source: Bitmap, edits: PageEdits): Bitmap =
        render(source, edits.copy(filter = PageFilter.Ink), OCR_SIDE)

    /** Crop + straighten + rotate only; the input to [applyFilter] for filter previews. */
    fun straighten(source: Bitmap, edits: PageEdits, maxSide: Int): Bitmap {
        ensureLoaded()
        val warped = warp(source, edits, maxSide)
        return toBitmap(warped).also { warped.release() }
    }

    fun applyFilter(straightened: Bitmap, filter: PageFilter): Bitmap {
        ensureLoaded()
        val rgb = toRgb(straightened)
        val filtered = Filters.apply(rgb, filter)
        rgb.release()
        return toBitmap(filtered).also { filtered.release() }
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 88) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun saveThumbnail(render: Bitmap, file: File) {
        val scale = min(1f, THUMB_SIDE.toFloat() / max(render.width, render.height))
        val thumb = Bitmap.createScaledBitmap(render, (render.width * scale).toInt().coerceAtLeast(1), (render.height * scale).toInt().coerceAtLeast(1), true)
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        file.parentFile?.mkdirs()
        file.outputStream().use { thumb.compress(format, 82, it) }
        if (thumb != render) thumb.recycle()
    }

    private fun warp(source: Bitmap, edits: PageEdits, maxSide: Int): Mat {
        val rgb = toRgb(source)
        val w = source.width
        val h = source.height
        val (outW, outH) = QuadGeometry.warpSize(edits.quad, w, h)
        val scale = min(1.0, maxSide.toDouble() / max(outW, outH))
        val dstW = (outW * scale).coerceAtLeast(1.0)
        val dstH = (outH * scale).coerceAtLeast(1.0)
        val src = MatOfPoint2f(*edits.quad.corners.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(dstW, 0.0), Point(dstW, dstH), Point(0.0, dstH))
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(rgb, out, transform, Size(dstW, dstH), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        listOf(rgb, src, dst, transform).forEach(Mat::release)
        val turn = when (((edits.rotation % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> null
        }
        if (turn != null) Core.rotate(out, out, turn)
        return out
    }

    private fun toRgb(bitmap: Bitmap): Mat {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat()
        Utils.bitmapToMat(argb, rgba)
        if (argb != bitmap) argb.recycle()
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return rgb
    }

    private fun toBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(mat, rgba, if (mat.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_RGB2RGBA)
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }
}
