package dev.olaf.oscanner.feature.capture

import dev.olaf.oscanner.core.imaging.QuadGeometry
import dev.olaf.oscanner.core.model.Quad

/** What the camera chrome shows for one analyzed frame. */
data class TrackerState(
    /** Smoothed page outline, or null when no page is in view. */
    val quad: Quad?,
    /** 0 → 1 while the page holds still; the shutter ring fills with it. */
    val progress: Float,
    /** True on the one frame where auto-capture should take the picture. */
    val fire: Boolean,
)

/**
 * Smooths detections and decides when to auto-capture (PLAN.md §5.4 step 5–6): each corner is
 * eased toward the latest detection; a page that stays within [stableIou] of where it settled for
 * [stableMillis] on a sharp frame fires once. It re-arms only after the page leaves or moves well
 * away, so one sheet is never captured twice.
 *
 * One frame's say isn't enough: an outline first shows once the next frame finds it again, and
 * one far from the shown outline replaces it only when the next frame agrees. A stray outline
 * on a single frame, on a patch of counter, say, never shows. An outline that takes in the shown
 * one and reaches further out (a strip of desk, out to a mat's edge) is the likeliest mistake,
 * so it needs [growFrames] frames in a row. Wobbles smaller than [still] leave the outline where
 * it is, so it doesn't shiver while the phone is held still.
 */
class AutoCaptureTracker(
    private val stableMillis: Long = 600,
    private val stableIou: Double = 0.95,
    private val minSharpness: Double = 40.0,
    private val lostMillis: Long = 400,
    private val smoothing: Float = 0.5f,
    private val growFrames: Int = 3,
    private val still: Float = 0.006f,
) {
    /**
     * Book mode: a spread and one of its pages are both good outlines, and a frame can find
     * either. Then an outline inside the shown one needs [growFrames] too, so it doesn't flick.
     */
    @Volatile var steadySize = false

    private var smoothed: Quad? = null
    /** A detection that disagrees with [smoothed], waiting for the next frames to confirm it. */
    private var pending: Quad? = null
    private var pendingFrames = 0
    private var anchor: Quad? = null
    private var anchorAt = 0L
    private var lastSeenAt = 0L
    private var armed = true
    private var firedQuad: Quad? = null

    fun onFrame(detected: Quad?, sharpness: Double, now: Long, autoEnabled: Boolean): TrackerState {
        if (detected == null) {
            if (now - lastSeenAt > lostMillis) reset()
            return TrackerState(smoothed, 0f, fire = false)
        }
        val shown = smoothed
        val grows = shown != null && (grows(shown, detected) || (steadySize && grows(detected, shown)))
        if (shown == null || grows || QuadGeometry.iou(shown, detected) < AGREE_IOU) {
            val candidate = pending
            pendingFrames = if (candidate != null && QuadGeometry.iou(candidate, detected) >= AGREE_IOU) pendingFrames + 1 else 1
            pending = detected
            // A page is still in view, even while it's not yet clear where: what's shown stays.
            if (shown != null && grows) lastSeenAt = now
            if (pendingFrames < if (grows) growFrames else 2) {
                // Unconfirmed: keep what's shown (or nothing) a little longer.
                if (shown != null && now - lastSeenAt > lostMillis) reset()
                return TrackerState(smoothed, 0f, fire = false)
            }
            // Frames agree on a new place: jump there rather than slide across.
            smoothed = null
            anchor = null
        }
        pending = null
        pendingFrames = 0
        lastSeenAt = now
        val eased = smoothed?.let { from ->
            if (QuadGeometry.maxCornerDistance(from, detected) < still) from else QuadGeometry.lerp(from, detected, smoothing)
        } ?: detected
        smoothed = eased

        val settled = anchor
        if (settled == null || QuadGeometry.iou(settled, eased) < stableIou) {
            anchor = eased
            anchorAt = now
        }
        firedQuad?.let { if (QuadGeometry.iou(it, eased) < REARM_IOU) rearm() }

        val progress = ((now - anchorAt).toFloat() / stableMillis).coerceIn(0f, 1f)
        val fire = autoEnabled && armed && progress >= 1f && sharpness >= minSharpness
        if (fire) {
            armed = false
            firedQuad = eased
        }
        return TrackerState(eased, if (armed) progress else 0f, fire)
    }

    /** Call after a manual capture too, so auto doesn't immediately take the same page again. */
    fun onCaptured() {
        armed = false
        firedQuad = smoothed
    }

    private fun rearm() {
        armed = true
        firedQuad = null
    }

    private fun reset() {
        smoothed = null
        anchor = null
        pending = null
        pendingFrames = 0
        rearm()
    }

    /** Whether [next] takes in [shown] and reaches clearly further out. */
    private fun grows(shown: Quad, next: Quad): Boolean {
        val a = QuadGeometry.area(shown.corners)
        val b = QuadGeometry.area(next.corners)
        if (b < GROW * a) return false
        val iou = QuadGeometry.iou(shown, next)
        val shared = iou * (a + b) / (1 + iou)
        return shared >= CONTAINED * a
    }

    private companion object {
        /** The page must move this far from where it was captured before auto fires again. */
        const val REARM_IOU = 0.8
        /** Outlines overlapping this much are the same page, moved a little. */
        const val AGREE_IOU = 0.85
        /** Larger by this share of its area, holding nearly all of what was shown. */
        const val GROW = 1.05
        const val CONTAINED = 0.95
    }
}
