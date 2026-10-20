package dev.olaf.oscanner.core.design

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Two fingers pinch to zoom and drag to move around; one finger is left to the [content], to
 * draw or paint with. The content sees its own unzoomed coordinates, so a stroke lands where the
 * finger is at any zoom. A second finger arriving mid-stroke takes the gesture over and the
 * stroke is dropped, as the content's own gesture is cancelled.
 */
@Composable
fun ZoomBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        // Before the content: two fingers are ours, whatever it would make of them.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            val centroid = event.calculateCentroid(useCurrent = true)
                            // Zoom about the fingers, then follow them; never past the edges.
                            val moved = centroid - (centroid - offset) * (next / scale) + event.calculatePan()
                            offset = Offset(
                                moved.x.coerceIn(size.width * (1 - next), 0f),
                                moved.y.coerceIn(size.height * (1 - next), 0f),
                            )
                            scale = next
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            content = content,
        )
    }
}

private const val MAX_ZOOM = 5f
