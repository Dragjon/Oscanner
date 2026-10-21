package dev.olaf.oscanner.feature.capture

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad

/**
 * The live outline is found on the whole camera frame (4:3), but the photo is cut to what the
 * screen shows, the frame's middle at the screen's shape, as the preview fills the screen. This
 * moves an outline from frame coordinates into the photo's.
 */
internal object FrameToPhoto {

    /** [quad] in a frame [frameAspect] wide per unit high, into a centered photo [photoAspect] wide. */
    fun map(quad: Quad, frameAspect: Float, photoAspect: Float): Quad {
        if (frameAspect <= 0f || photoAspect <= 0f) return quad
        return if (photoAspect < frameAspect) {
            // A narrower photo: the frame's full height, the middle of its width.
            val share = photoAspect / frameAspect
            Quad.of(quad.corners.map { Corner(((it.x - (1f - share) / 2f) / share).coerceIn(0f, 1f), it.y) })
        } else {
            val share = frameAspect / photoAspect
            Quad.of(quad.corners.map { Corner(it.x, ((it.y - (1f - share) / 2f) / share).coerceIn(0f, 1f)) })
        }
    }
}
