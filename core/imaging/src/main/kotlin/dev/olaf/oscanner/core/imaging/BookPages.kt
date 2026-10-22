package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Quad

/** The pages of an open book, as they're read. */
object BookPages {

    /**
     * The two halves of a split spread, the page on the left first once the photo is turned
     * [turn] degrees clockwise to read upright: a book photographed turned has its pages one
     * above the other, and which of them is read first depends on which way it was turned.
     */
    fun inReadingOrder(halves: Pair<Quad, Quad>, turn: Int): Pair<Quad, Quad> {
        // How far across each half's middle is, left to right, once turned.
        fun across(q: Quad): Double {
            val x = q.corners.sumOf { it.x.toDouble() } / 4
            val y = q.corners.sumOf { it.y.toDouble() } / 4
            return when (((turn % 360) + 360) % 360) {
                90 -> 1 - y
                180 -> 1 - x
                270 -> y
                else -> x
            }
        }
        return if (across(halves.second) < across(halves.first)) halves.second to halves.first else halves
    }
}
