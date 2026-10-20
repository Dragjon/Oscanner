package dev.olaf.oscanner

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.design.ChalkSnowman
import dev.olaf.oscanner.core.design.rememberOlafQuote

/**
 * What shows while the app starts: the chalk snowman, bobbing, over one of Olaf's lines from
 * Frozen, a different one each launch.
 */
@Composable
fun StartupScreen() {
    val colors = MaterialTheme.colorScheme
    val quote = rememberOlafQuote()
    // Rises from where the system splash left him, so the two meet without a jump.
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        initialValue = 0f,
        targetValue = -8f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // Dead center and this size: the same as the snowman drawn on the system splash.
        ChalkSnowman(
            Modifier
                .align(Alignment.Center)
                .size(width = SNOWMAN_W, height = SNOWMAN_H)
                .graphicsLayer { translationY = bob.dp.toPx() },
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = maxHeight / 2 + SNOWMAN_H / 2 + 24.dp, start = 32.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "“$quote”",
                style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic),
                color = colors.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp).semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "— Olaf",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private val SNOWMAN_W = 120.dp
private val SNOWMAN_H = 132.dp
