package dev.olaf.oscanner.core.design

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * For a screen with text fields: a tap on anything that isn't itself tappable, or the keyboard
 * going away (the back gesture closes it), ends the typing, so no field is left with a blinking
 * cursor after its keyboard is gone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.endTypingOutside(): Modifier {
    val focus = LocalFocusManager.current
    val keyboard = WindowInsets.isImeVisible
    LaunchedEffect(keyboard) { if (!keyboard) focus.clearFocus() }
    return pointerInput(focus) { detectTapGestures(onTap = { focus.clearFocus() }) }
}

/**
 * A row that scrolls sideways, faded at whichever end has more out of view, so it's plain there
 * are more tools to find. [state] is the row's own scroll state.
 */
fun Modifier.fadingEdges(state: androidx.compose.foundation.ScrollState, width: androidx.compose.ui.unit.Dp = 28.dp): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = width.toPx()
            if (state.value > 0) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (state.value < state.maxValue) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                    topLeft = Offset(size.width - fade, 0f),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
