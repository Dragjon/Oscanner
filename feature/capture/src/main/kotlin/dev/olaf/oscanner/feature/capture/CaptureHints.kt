package dev.olaf.oscanner.feature.capture

import dev.olaf.oscanner.core.imaging.QuadGeometry
import dev.olaf.oscanner.core.model.Quad
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Something about the shot worth a word, most pressing first. */
enum class CaptureHint(val warning: Boolean) {
    Finger(warning = true),
    Dark(warning = true),
    Steep(warning = true),
    Far(warning = false),
    Handwriting(warning = false),
}

/**
 * Picks the one hint to show from what recent frames saw. A hint appears once most of the last
 * dozen frames agree, stays long enough to read, and goes once the problem has been gone a
 * moment, so it never flickers. Not thread-safe; the camera's one analysis thread calls it.
 */
internal class CaptureHints(
    private val window: Int = 12,
    private val needed: Int = 7,
    private val minShowMillis: Long = 1500,
    private val clearMillis: Long = 1000,
) {
    private val recent = ArrayDeque<Set<CaptureHint>>()
    private var shown: CaptureHint? = null
    private var shownAt = 0L
    private var clearSince = 0L

    /** [quad] in normalized screen coordinates, [aspect] the screen frame's width / height. */
    fun onFrame(quad: Quad?, aspect: Float, signals: FrameSignals?, flashOn: Boolean, now: Long): CaptureHint? {
        recent.addLast(active(quad, aspect, signals, flashOn))
        if (recent.size > window) recent.removeFirst()
        val agreed = CaptureHint.entries.firstOrNull { hint -> recent.count { hint in it } >= needed }
        val current = shown
        when {
            agreed == current -> clearSince = 0L
            current != null && now - shownAt < minShowMillis -> Unit
            agreed != null -> {
                shown = agreed
                shownAt = now
                clearSince = 0L
            }
            else -> {
                if (clearSince == 0L) clearSince = now
                if (now - clearSince >= clearMillis) shown = null
            }
        }
        return shown
    }

    fun reset() {
        recent.clear()
        shown = null
        clearSince = 0L
    }

    private fun active(quad: Quad?, aspect: Float, signals: FrameSignals?, flashOn: Boolean): Set<CaptureHint> = buildSet {
        if (signals != null && signals.brightness < DARK && !flashOn) add(CaptureHint.Dark)
        if (quad == null) return@buildSet
        if (signals != null && signals.skinAtEdges > FINGER_SHARE) add(CaptureHint.Finger)
        if (signals != null && signals.penOnPage > PEN_SHARE) add(CaptureHint.Handwriting)
        if (steep(quad, aspect)) add(CaptureHint.Steep)
        if (QuadGeometry.area(quad.corners) < FAR_AREA) add(CaptureHint.Far)
    }

    /**
     * Shot at a slant: corners far from square, or one side much shorter than the side across,
     * once the frame's stretch is taken out of the normalized coordinates.
     */
    private fun steep(quad: Quad, aspect: Float): Boolean {
        val c = quad.corners.map { it.x * aspect to it.y }
        fun side(i: Int) = hypot(c[(i + 1) % 4].first - c[i].first, c[(i + 1) % 4].second - c[i].second)
        val across = min(side(0), side(2)) / max(side(0), side(2)).coerceAtLeast(1e-6f)
        val down = min(side(1), side(3)) / max(side(1), side(3)).coerceAtLeast(1e-6f)
        val squareness = QuadGeometry.angles(Quad.of(c.map { dev.olaf.oscanner.core.model.Corner(it.first, it.second) }))
            .sumOf { abs(it - 90.0) } / 4
        return min(across, down) < STEEP_RATIO || squareness > STEEP_DEGREES
    }

    private companion object {
        const val DARK = 55.0
        const val FINGER_SHARE = 0.06f
        const val PEN_SHARE = 0.012f
        const val FAR_AREA = 0.22
        const val STEEP_RATIO = 0.72f
        const val STEEP_DEGREES = 14.0
    }
}
