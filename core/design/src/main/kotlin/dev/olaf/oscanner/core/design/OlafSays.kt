package dev.olaf.oscanner.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One of Olaf's lines from Frozen, picked at random and kept while the screen is around. */
@Composable
fun rememberOlafQuote(): String {
    val quotes = stringArrayResource(R.array.design_olaf_quotes)
    val index = rememberSaveable { quotes.indices.random() }
    return quotes[index.coerceIn(quotes.indices)]
}

/** The chalk snowman with one of his lines in a speech bubble above him. */
@Composable
fun OlafSays(modifier: Modifier = Modifier, quote: String = rememberOlafQuote()) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            shape = MaterialTheme.shapes.large,
        ) {
            Text(
                quote,
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
        // The bubble's tail, pointing down at him.
        val tail = colors.surfaceContainerHigh
        Canvas(Modifier.size(width = 20.dp, height = 10.dp)) {
            drawPath(
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width * 0.35f, size.height)
                    close()
                },
                tail,
            )
        }
        ChalkSnowman(Modifier.size(width = 140.dp, height = 154.dp))
    }
}
