package dev.olaf.oscanner.feature.capture

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureTrackerTest {

    private val page = Quad(Corner(0.2f, 0.2f), Corner(0.8f, 0.2f), Corner(0.8f, 0.8f), Corner(0.2f, 0.8f))
    private val elsewhere = Quad(Corner(0.0f, 0.0f), Corner(0.4f, 0.0f), Corner(0.4f, 0.4f), Corner(0.0f, 0.4f))

    /** Feeds [quad] every 50 ms from [start] to [end] and returns the times auto-capture fired. */
    private fun AutoCaptureTracker.feed(quad: Quad?, start: Long, end: Long, sharp: Double = 100.0, auto: Boolean = true): List<Long> =
        (start..end step 50).filter { onFrame(quad, sharp, it, auto).fire }

    @Test
    fun firesOnceAfterPageHoldsStill() {
        val tracker = AutoCaptureTracker()
        val fired = tracker.feed(page, 0, 3000)
        // Shown from the second frame (50 ms), held 600 ms.
        assertEquals(listOf(650L), fired)
    }

    @Test
    fun aStrayOutlineOnOneFrameNeverShows() {
        val tracker = AutoCaptureTracker()
        assertNull(tracker.onFrame(elsewhere, 100.0, 0, true).quad)
        assertNull(tracker.onFrame(null, 100.0, 50, true).quad)
    }

    @Test
    fun aStrayOutlineDoesNotPullTheShownOneAway() {
        val tracker = AutoCaptureTracker()
        tracker.feed(page, 0, 300)
        assertEquals(page, tracker.onFrame(elsewhere, 100.0, 350, true).quad)
        assertEquals(page, tracker.onFrame(page, 100.0, 400, true).quad)
    }

    @Test
    fun anOutlineSeenTwiceElsewhereIsAdopted() {
        val tracker = AutoCaptureTracker()
        tracker.feed(page, 0, 300)
        tracker.onFrame(elsewhere, 100.0, 350, true)
        assertEquals(elsewhere, tracker.onFrame(elsewhere, 100.0, 400, true).quad)
    }

    @Test
    fun anOutlineJumpingOutForAFrameOrTwoNeverShows() {
        val tracker = AutoCaptureTracker()
        tracker.feed(page, 0, 300)
        // Out to a mat's edge on the right and below: 8% bigger, holding the page.
        val wider = Quad(Corner(0.2f, 0.2f), Corner(0.84f, 0.2f), Corner(0.84f, 0.84f), Corner(0.2f, 0.84f))
        assertEquals(page, tracker.onFrame(wider, 100.0, 350, true).quad)
        assertEquals(page, tracker.onFrame(wider, 100.0, 400, true).quad)
        assertEquals(page, tracker.onFrame(page, 100.0, 450, true).quad)
        // Held for three frames, it's believed: the page really is bigger (the phone moved closer).
        tracker.onFrame(wider, 100.0, 500, true)
        tracker.onFrame(wider, 100.0, 550, true)
        assertEquals(wider, tracker.onFrame(wider, 100.0, 600, true).quad)
    }

    @Test
    fun inBookModeOnePageDoesNotReplaceTheSpreadAtOnce() {
        val tracker = AutoCaptureTracker().apply { steadySize = true }
        tracker.feed(page, 0, 300)
        // The left page alone: half the spread.
        val left = Quad(Corner(0.2f, 0.2f), Corner(0.5f, 0.2f), Corner(0.5f, 0.8f), Corner(0.2f, 0.8f))
        assertEquals(page, tracker.onFrame(left, 100.0, 350, true).quad)
        assertEquals(page, tracker.onFrame(left, 100.0, 400, true).quad)
        assertEquals(page, tracker.onFrame(page, 100.0, 450, true).quad)
    }

    @Test
    fun aTinyWobbleLeavesTheOutlineStill() {
        val tracker = AutoCaptureTracker()
        tracker.feed(page, 0, 300)
        val nudged = Quad(Corner(0.203f, 0.2f), Corner(0.803f, 0.202f), Corner(0.8f, 0.8f), Corner(0.2f, 0.797f))
        assertEquals(page, tracker.onFrame(nudged, 100.0, 350, true).quad)
    }

    @Test
    fun doesNotFireOnBlurryFrames() {
        val tracker = AutoCaptureTracker()
        assertTrue(tracker.feed(page, 0, 2000, sharp = 5.0).isEmpty())
    }

    @Test
    fun doesNotFireInManualMode() {
        val tracker = AutoCaptureTracker()
        assertTrue(tracker.feed(page, 0, 2000, auto = false).isEmpty())
    }

    @Test
    fun rearmsAfterPageLeavesView() {
        val tracker = AutoCaptureTracker()
        assertEquals(1, tracker.feed(page, 0, 1000).size)
        tracker.feed(null, 1050, 1600)
        val again = tracker.feed(page, 1650, 3000)
        assertEquals(1, again.size)
    }

    @Test
    fun rearmsWhenNextPageIsPlacedElsewhere() {
        val tracker = AutoCaptureTracker()
        assertEquals(1, tracker.feed(page, 0, 1000).size)
        val next = tracker.feed(elsewhere, 1050, 3000)
        assertEquals(1, next.size)
    }

    @Test
    fun manualCaptureDisarmsAuto() {
        val tracker = AutoCaptureTracker()
        tracker.feed(page, 0, 200)
        tracker.onCaptured()
        assertTrue(tracker.feed(page, 250, 2000).isEmpty())
    }

    @Test
    fun keepsLastQuadThroughBriefDropouts() {
        val tracker = AutoCaptureTracker()
        tracker.feed(page, 0, 300)
        val state = tracker.onFrame(null, 100.0, 450, true)
        assertFalse(state.quad == null)
        val lost = tracker.onFrame(null, 100.0, 900, true)
        assertNull(lost.quad)
    }
}
