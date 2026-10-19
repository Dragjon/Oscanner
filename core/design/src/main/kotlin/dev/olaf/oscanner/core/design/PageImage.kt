package dev.olaf.oscanner.core.design

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Decodes page images at about the size they're shown, turned upright per EXIF. Paths of renders
 * change on every re-render, so caching by path + size is safe.
 */
object PageImages {

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(path: String, maxSide: Int): Bitmap? = cache.get(key(path, maxSide))

    fun load(path: String, maxSide: Int): Bitmap? {
        cached(path, maxSide)?.let { return it }
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = if (path.endsWith(".jpg", ignoreCase = true)) {
            runCatching { ExifInterface(path).rotationDegrees }.getOrDefault(0)
        } else {
            0
        }
        val upright = if (degrees == 0) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
                .also { if (it != decoded) decoded.recycle() }
        }
        cache.put(key(path, maxSide), upright)
        return upright
    }

    private fun key(path: String, maxSide: Int) = "$path@$maxSide"
}

/** A page image for Compose, loaded off the main thread. Null while loading or if missing. */
@Composable
fun rememberPageImage(path: String?, maxSide: Int): State<ImageBitmap?> =
    produceState(initialValue = path?.let { PageImages.cached(it, maxSide)?.asImageBitmap() }, path, maxSide) {
        value = path?.let { p -> withContext(Dispatchers.IO) { runCatching { PageImages.load(p, maxSide) }.getOrNull() }?.asImageBitmap() }
    }
