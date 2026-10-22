package dev.olaf.oscanner.feature.capture

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureHintsTest {

    private val page = Quad.of(listOf(Corner(0.15f, 0.15f), Corner(0.85f, 0.15f), Corner(0.85f, 0.85f), Corner(0.15f, 0.85f)))
    private val clean = FrameSignals(brightness = 150.0, skinAtEdges = 0f, penOnPage = 0f)
    private val thumb = clean.copy(skinAtEdges = 0.2f)

    private fun CaptureHints.run(frames: Int, signals: FrameSignals, from: Long = 0, quad: Quad? = page): CaptureHint? {
        var hint: CaptureHint? = null
        repeat(frames) { hint = onFrame(quad, 1f, signals, flashOn = false, now = from + it * 50L) }
        return hint
    }

    @Test
    fun aGoodShotHasNoHint() {
        assertNull(CaptureHints().run(20, clean))
    }

    @Test
    fun aHintNeedsMostRecentFramesToAgree() {
        val hints = CaptureHints()
        assertNull(hints.run(6, thumb))
        assertEquals(CaptureHint.Finger, hints.run(1, thumb, from = 300))
    }

    @Test
    fun aHintStaysLongEnoughToReadThenGoes() {
        val hints = CaptureHints()
        hints.run(10, thumb)
        // The thumb moves away at once; the hint holds for a moment, then clears.
        assertEquals(CaptureHint.Finger, hints.onFrame(page, 1f, clean, false, now = 900))
        assertNull(hints.run(30, clean, from = 3000))
    }

    @Test
    fun theMostPressingHintWins() {
        val hints = CaptureHints()
        val both = thumb.copy(penOnPage = 0.05f)
        assertEquals(CaptureHint.Finger, hints.run(12, both))
    }

    @Test
    fun darknessIsOnlyMentionedWithTheFlashOff() {
        val dark = clean.copy(brightness = 30.0)
        assertEquals(CaptureHint.Dark, CaptureHints().run(12, dark))
        val withFlash = CaptureHints()
        var hint: CaptureHint? = null
        repeat(12) { hint = withFlash.onFrame(page, 1f, dark, flashOn = true, now = it * 50L) }
        assertNull(hint)
    }

    @Test
    fun aSlantedOutlineAsksForAFlatterShot() {
        val slanted = Quad.of(listOf(Corner(0.35f, 0.2f), Corner(0.65f, 0.2f), Corner(0.95f, 0.9f), Corner(0.05f, 0.9f)))
        assertEquals(CaptureHint.Steep, CaptureHints().run(12, clean, quad = slanted))
    }

    @Test
    fun aSmallPageAsksToComeCloser() {
        val small = Quad.of(listOf(Corner(0.4f, 0.4f), Corner(0.6f, 0.4f), Corner(0.6f, 0.6f), Corner(0.4f, 0.6f)))
        assertEquals(CaptureHint.Far, CaptureHints().run(12, clean, quad = small))
    }

    @Test
    fun aCardIsSmallerThanAPageBeforeItAsksToComeCloser() {
        // A card taking a sixth of the frame, or even a thirteenth, is close enough; a seventieth isn't.
        val card = Quad.of(listOf(Corner(0.3f, 0.3f), Corner(0.7f, 0.3f), Corner(0.7f, 0.7f), Corner(0.3f, 0.7f)))
        val far = Quad.of(listOf(Corner(0.375f, 0.35f), Corner(0.625f, 0.35f), Corner(0.625f, 0.65f), Corner(0.375f, 0.65f)))
        val distant = Quad.of(listOf(Corner(0.45f, 0.45f), Corner(0.55f, 0.45f), Corner(0.55f, 0.6f), Corner(0.45f, 0.6f)))
        fun CaptureHints.card(quad: Quad): CaptureHint? {
            var hint: CaptureHint? = null
            repeat(12) { hint = onFrame(quad, 1f, clean, flashOn = false, now = it * 50L, card = true) }
            return hint
        }
        assertNull(CaptureHints().card(card))
        assertNull(CaptureHints().card(far))
        assertEquals(CaptureHint.Far, CaptureHints().card(distant))
    }
}
