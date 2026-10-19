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
 */
class AutoCaptureTracker(
    private val stableMillis: Long = 600,
    private val stableIou: Double = 0.95,
    private val minSharpness: Double = 40.0,
    private val lostMillis: Long = 400,
    private val smoothing: Float = 0.5f,
) {
    private var smoothed: Quad? = null
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
        lastSeenAt = now
        val eased = smoothed?.let { QuadGeometry.lerp(it, detected, smoothing) } ?: detected
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
        rearm()
    }

    private companion object {
        /** The page must move this far from where it was captured before auto fires again. */
        const val REARM_IOU = 0.8
    }
}
