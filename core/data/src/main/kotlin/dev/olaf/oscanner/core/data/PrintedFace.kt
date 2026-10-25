package dev.olaf.oscanner.core.data

/**
 * Whether print is set in a serif face, told by how its strokes vary in width. Serif faces are
 * drawn as with a broad pen: upright strokes thick, level ones thin (Times, Georgia, Garamond).
 * Sans-serif faces keep every stroke about as wide (Arial, Calibri, Verdana). Across a line of
 * ink, a run is an upright stroke's width; down a column, a level stroke's; runs longer than a
 * stroke could be (a letter's height, a bar's length) are left out.
 *
 * Rendered at 20 to 48 px, serif faces came out at 1.15 to 1.43 and sans-serif ones at 0.94 to
 * 1.10. Smaller than [LEAST_SIZE] the strokes are a pixel or two either way and can't be told.
 */
internal class PrintedFace {
    private var across = 0L
    private var acrossRuns = 0
    private var down = 0L
    private var downRuns = 0
    private var sizes = 0.0
    private var regions = 0

    /**
     * Adds a region of print: [luma] (0–255, [w] wide, row by row), where ink is darker than
     * [threshold] (or lighter, for light print on dark when [light]), set [size] pixels tall.
     */
    fun add(luma: IntArray, w: Int, threshold: Int, light: Boolean, size: Float) {
        val h = luma.size / w
        val longest = (size * STROKE).toInt().coerceAtLeast(1)
        fun ink(i: Int) = if (light) luma[i] > threshold else luma[i] < threshold
        for (y in 0 until h) {
            var n = 0
            for (x in 0..w) {
                if (x < w && ink(y * w + x)) {
                    n++
                } else if (n > 0) {
                    if (n <= longest) { across += n; acrossRuns++ }
                    n = 0
                }
            }
        }
        for (x in 0 until w) {
            var n = 0
            for (y in 0..h) {
                if (y < h && ink(y * w + x)) {
                    n++
                } else if (n > 0) {
                    if (n <= longest) { down += n; downRuns++ }
                    n = 0
                }
            }
        }
        sizes += size
        regions++
    }

    /** Upright strokes' mean width over level strokes'; null with too little print to go by. */
    val contrast: Double?
        get() = if (acrossRuns < MIN_RUNS || downRuns < MIN_RUNS) null else (across.toDouble() / acrossRuns) / (down.toDouble() / downRuns)

    /** True for serif, false for sans-serif, null when the print is too small or too little to tell. */
    val serif: Boolean?
        get() {
            if (regions == 0 || sizes / regions < LEAST_SIZE) return null
            return contrast?.let { it >= SERIF_CONTRAST }
        }

    private companion object {
        /** A stroke is at most this share of the type size wide. */
        const val STROKE = 0.35f
        const val MIN_RUNS = 200
        const val LEAST_SIZE = 18.0
        const val SERIF_CONTRAST = 1.12
    }
}
