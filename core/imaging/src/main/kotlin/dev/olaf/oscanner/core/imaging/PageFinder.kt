package dev.olaf.oscanner.core.imaging

import org.opencv.core.Core
import org.opencv.core.Mat

/**
 * A second opinion on where the page is, for frames where [DocumentDetector]'s own search finds
 * nothing: pages barely lighter than the counter they lie on, or on busy patterns.
 *
 * Given the frame in gray, it returns how likely each spot is to be page, as an 8-bit image of
 * any size (0 = surely not, 255 = surely page), or null if it has no opinion.
 */
fun interface PageFinder {
    fun pageMask(gray: Mat): Mat?
}

/**
 * This finder for frames that reach the screen turned [toScreen] degrees clockwise, as a camera's
 * do: it was trained on upright pictures, so each frame is turned upright for it and its mask
 * turned back. Fed a sideways frame squeezed into its portrait input, it sees no card or page.
 */
fun PageFinder.turned(toScreen: Int): PageFinder {
    val (there, back) = when (Math.floorMod(toScreen, 360)) {
        90 -> Core.ROTATE_90_CLOCKWISE to Core.ROTATE_90_COUNTERCLOCKWISE
        180 -> Core.ROTATE_180 to Core.ROTATE_180
        270 -> Core.ROTATE_90_COUNTERCLOCKWISE to Core.ROTATE_90_CLOCKWISE
        else -> return this
    }
    return PageFinder { gray ->
        val upright = Mat()
        Core.rotate(gray, upright, there)
        val mask = pageMask(upright)
        upright.release()
        mask?.let { Mat().also { turnedBack -> Core.rotate(it, turnedBack, back) }.also { _ -> it.release() } }
    }
}
