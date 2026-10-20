package dev.olaf.oscanner.core.design

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange

/**
 * Like [androidx.compose.foundation.gestures.detectDragGestures], but the drag starts where the
 * finger touched down, not where it had moved past the touch slop: a pen stroke begins under the
 * finger instead of a few millimetres along. The first [onDrag] covers the whole way from the
 * touch-down to the slop.
 */
suspend fun PointerInputScope.detectDragsFromTouch(
    onDragStart: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    var start: PointerInputChange?
    do {
        start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
    } while (start != null && !start.isConsumed)
    if (start == null) return@awaitEachGesture
    onDragStart(down.position)
    onDrag(start, start.position - down.position)
    val finished = drag(start.id) { change ->
        onDrag(change, change.positionChange())
        change.consume()
    }
    if (finished) onDragEnd() else onDragCancel()
}
