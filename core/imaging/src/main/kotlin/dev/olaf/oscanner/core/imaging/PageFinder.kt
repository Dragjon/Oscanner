package dev.olaf.oscanner.core.imaging

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
