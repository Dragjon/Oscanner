package dev.olaf.oscanner.core.design

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ripple
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.model.Document
import dev.olaf.oscanner.core.model.ProcessingState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CardHeight = 116.dp

/**
 * One document, always with the same plate: cover, name, then pages · date · size.
 * Height is fixed so a status appearing or clearing never moves the list.
 * While [selected] is non-null the library is choosing documents: the card shows a checkmark.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentCard(
    document: Document,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(CardHeight)
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier),
        shape = MaterialTheme.shapes.large,
        color = if (selected == true) colors.primaryContainer else colors.surfaceContainer,
        border = if (selected == true) BorderStroke(2.dp, colors.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                PageThumbnail(path = document.coverThumbPath, modifier = Modifier.size(width = 64.dp, height = 84.dp))
                if (selected != null) {
                    SelectMark(selected, Modifier.align(Alignment.TopStart).padding(4.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    document.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    documentMeta(document),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedContent(
                    targetState = document.state,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "documentState",
                ) { state ->
                    if (state != ProcessingState.Idle) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            StatusChip(state)
                        }
                    }
                }
            }
        }
    }
}

/** A round checkmark: filled orange when chosen, an empty ring when not. */
@Composable
private fun SelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.surfaceContainer.copy(alpha = 0.85f))
            .border(2.dp, if (selected) colors.primary else colors.onSurfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun documentMeta(document: Document): String {
    val context = LocalContext.current
    val pages = pluralStringResource(R.plurals.design_pages, document.pageCount, document.pageCount)
    val date = remember(document.updatedAt) { shortDate(document.updatedAt) }
    val size = remember(document.sizeBytes) { Formatter.formatShortFileSize(context, document.sizeBytes) }
    return "$pages · $date · $size"
}

private fun shortDate(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val pattern = if (date.year == LocalDate.now(zone).year) "d MMM" else "d MMM yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

/** Background work on a document, said in words. Neutral colors: a status is not an action. */
@Composable
fun StatusChip(state: ProcessingState, modifier: Modifier = Modifier) {
    val labelRes = when (state) {
        ProcessingState.Idle -> return
        ProcessingState.Enhancing -> R.string.design_state_enhancing
        ProcessingState.ReadingText -> R.string.design_state_reading_text
        ProcessingState.Failed -> R.string.design_state_failed
    }
    WorkChip(stringResource(labelRes), modifier, failed = state == ProcessingState.Failed)
}

/**
 * A small pill saying what's going on in the background, with a spinner (or, when it
 * [failed], a warning): out of the way, so nothing waits on it, but the app never looks stuck.
 */
@Composable
fun WorkChip(label: String, modifier: Modifier = Modifier, failed: Boolean = false) {
    val ink = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.height(24.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = ink,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (failed) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(14.dp))
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = ink,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A page image in a rounded well, letterboxed so receipts and ID cards keep their shape. */
@Composable
fun PageThumbnail(path: String?, modifier: Modifier = Modifier) {
    val bitmap by rememberPageImage(path, maxSide = 320)
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}
