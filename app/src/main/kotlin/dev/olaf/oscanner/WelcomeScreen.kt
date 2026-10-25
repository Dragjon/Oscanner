package dev.olaf.oscanner

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.design.ChalkSnowman
import dev.olaf.oscanner.core.design.LocalOscannerExtras
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.design.pressScale
import dev.olaf.oscanner.core.design.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.sin

/**
 * The tour on first opening, on one screen: what Oscanner does, shown rather than listed. Each
 * tile holds a phone photo and what the app made of it, a paper-white line wiping from one to
 * the other; the pictures are the app's own output from the photos beside them. Tap a tile to
 * drag the line yourself. How it stands against CamScanner is a sheet away; Start scanning is
 * always in reach. With animations off in system settings, the tiles show the results, still.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    var comparing by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(140.dp) }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Spacer(Modifier.height(16.dp))
            Greeting(reduced)
            Spacer(Modifier.height(8.dp))
            // A bento of the app at work: the receipt tall on the left, as tall as the two beside it.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                WipeTile(TILES[0], 0, reduced, Modifier.weight(1f).fillMaxHeight(), picture = null) { open = 0 }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GAP)) {
                    WipeTile(TILES[1], 1, reduced, Modifier.fillMaxWidth(), picture = SHORT) { open = 1 }
                    WipeTile(TILES[2], 2, reduced, Modifier.fillMaxWidth(), picture = SHORT) { open = 2 }
                }
            }
            WipeTile(TILES[3], 3, reduced, Modifier.fillMaxWidth(), picture = WIDE) { open = 3 }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                WipeTile(TILES[4], 4, reduced, Modifier.weight(1f), picture = SQUARE) { open = 4 }
                PrivateTile(Modifier.weight(1f).fillMaxHeight())
            }
            // Room to scroll the last tiles clear of the pinned bar.
            Spacer(Modifier.height(barHeight + 16.dp))
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(colors.background),
        )
        Surface(
            color = colors.background,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned { barHeight = with(density) { it.size.height.toDp() } },
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(top = 4.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextButton(onClick = { comparing = true }) {
                    Text(stringResource(R.string.welcome_compare), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
                PrimaryPill(
                    icon = Icons.Rounded.DocumentScanner,
                    label = stringResource(R.string.welcome_start),
                    onClick = onDone,
                )
            }
        }
    }
    open?.let { index -> TileSheet(TILES[index], onDismiss = { open = null }) }
    if (comparing) CompareSheet(onDismiss = { comparing = false })
}

/** Olaf, small, with a wave on arrival, beside who he is and the promise. */
@Composable
private fun Greeting(reduced: Boolean) {
    val colors = MaterialTheme.colorScheme
    val wave = remember { Animatable(0f) }
    LaunchedEffect(reduced) {
        if (!reduced) wave.animateTo(1f, tween(WAVE_MS, easing = LinearEasing))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 56.dp, height = 64.dp)
                .graphicsLayer {
                    // Two leans each way, settling: a wave from the whole snowman.
                    rotationZ = sin(wave.value * 4 * PI).toFloat() * 9f * (1f - wave.value)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                },
        ) {
            ChalkSnowman(Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                stringResource(R.string.welcome_hello_title),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.welcome_promise), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
    }
}

/** A tile's pictures, a phone photo and what Oscanner made of it, and what it says about them. */
private class Tile(
    @DrawableRes val before: Int,
    @DrawableRes val after: Int,
    @StringRes val caption: Int,
    @StringRes val detail: Int,
    /** Its pictures' width over height, for showing them whole when opened. */
    val aspect: Float,
    /** Show the result whole on paper rather than cropped to fill: a receipt's totals sit at its edge. */
    val whole: Boolean = false,
)

private val TILES = listOf(
    Tile(R.drawable.welcome_receipt_before, R.drawable.welcome_receipt_after, R.string.welcome_tile_receipt, R.string.welcome_tile_receipt_detail, 0.6f, whole = true),
    Tile(R.drawable.welcome_pen_before, R.drawable.welcome_pen_after, R.string.welcome_tile_pen, R.string.welcome_tile_pen_detail, 480f / 380),
    Tile(R.drawable.welcome_book_before, R.drawable.welcome_book_after, R.string.welcome_tile_book, R.string.welcome_tile_book_detail, 480f / 380),
    Tile(R.drawable.welcome_translate_before, R.drawable.welcome_translate_after, R.string.welcome_tile_translate, R.string.welcome_tile_translate_detail, 960f / 560),
    Tile(R.drawable.welcome_photo_before, R.drawable.welcome_photo_after, R.string.welcome_tile_photo, R.string.welcome_tile_photo_detail, 480f / 540),
)

/**
 * A tile wiping between [tile]'s photo and result on a loop, each tile its own beat behind the
 * one before so the grid breathes rather than flashes. Tapped, it opens to look closer.
 */
@Composable
private fun WipeTile(tile: Tile, index: Int, reduced: Boolean, modifier: Modifier, picture: Dp?, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val caption = stringResource(tile.caption)
    // How much of the photo still shows, from the left: 1 all photo, 0 all result.
    val shown = if (reduced) {
        0f
    } else {
        val loop = rememberInfiniteTransition(label = "wipe")
        loop.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = CYCLE_MS
                    1f at 0
                    1f at HOLD_MS using FastOutSlowInEasing
                    0f at HOLD_MS + SWEEP_MS
                    0f at CYCLE_MS - SWEEP_MS using FastOutSlowInEasing
                    1f at CYCLE_MS
                },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(index * STAGGER_MS),
            ),
            label = "shown",
        ).value
    }
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainer,
        interactionSource = interaction,
        modifier = modifier
            .pressScale(interaction)
            .semantics(mergeDescendants = true) { contentDescription = caption },
    ) {
        Column(Modifier.padding(8.dp)) {
            val well = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            Wipe(tile, shown, if (picture == null) well.weight(1f).heightIn(min = TALL_MIN) else well.height(picture))
            Text(
                caption,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
            )
        }
    }
}

/** [tile]'s result with its photo over the left [shown] of it, and the paper-white line between. */
@Composable
private fun Wipe(tile: Tile, shown: Float, modifier: Modifier, line: Dp = 2.dp) {
    Box(modifier.background(if (tile.whole) RECEIPT_PAPER else MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Image(
            painterResource(tile.after),
            contentDescription = null,
            contentScale = if (tile.whole) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Image(
            painterResource(tile.before),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    clipRect(right = size.width * shown) { this@drawWithContent.drawContent() }
                    if (shown in 0.001f..0.999f) {
                        // Paper white between two dark keylines, so it reads over white paper too.
                        val x = size.width * shown
                        val w = line.toPx()
                        drawLine(KEYLINE, Offset(x, 0f), Offset(x, size.height), strokeWidth = w + 2 * 1.dp.toPx())
                        drawLine(PAPER, Offset(x, 0f), Offset(x, size.height), strokeWidth = w)
                    }
                },
        )
    }
}

/** The privacy promise, set in type: the one tile with nothing to show. */
@Composable
private fun PrivateTile(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Column {
                Text(
                    stringResource(R.string.welcome_tile_private),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.welcome_tile_private_detail), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** A tile opened: its pictures big, the line under the finger, and two plain lines on what it does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TileSheet(tile: Tile, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var shown by remember { mutableFloatStateOf(0.5f) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surfaceContainer) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding()) {
            Text(stringResource(tile.caption), style = MaterialTheme.typography.headlineSmall, color = colors.onSurface, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(16.dp))
            BoxWithHandle(shown) {
                Wipe(
                    tile,
                    shown,
                    Modifier
                        .fillMaxWidth()
                        // Whole, but never taller than a phone screen leaves room for.
                        .aspectRatio(tile.aspect.coerceAtLeast(0.75f))
                        .clip(RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, _ -> shown = (change.position.x / size.width).coerceIn(0f, 1f) }
                        }
                        .pointerInput(Unit) { detectTapGestures { shown = (it.x / size.width).coerceIn(0f, 1f) } },
                    line = 3.dp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.welcome_photo_label), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(stringResource(R.string.welcome_drag_hint), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(stringResource(R.string.welcome_result_label), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(tile.detail), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        }
    }
}

/** The opened picture with a round grip on the line, so it's plain the line moves. */
@Composable
private fun BoxWithHandle(shown: Float, content: @Composable BoxScope.() -> Unit) {
    Box {
        content()
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown.coerceIn(0.02f, 0.98f))
                    .height(36.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .graphicsLayer { translationX = 18.dp.toPx() }
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(PAPER),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = Color(0xFF2B241F), modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** How a row of the table comes out for each app. */
private enum class Mark { Yes, No, Partly }

private data class Comparison(val feature: Int, val oscanner: Mark, val oscannerNote: Int, val camscanner: Mark, val camscannerNote: Int)

private val COMPARISON = listOf(
    Comparison(R.string.versus_price, Mark.Yes, R.string.versus_price_ours, Mark.No, R.string.versus_price_theirs),
    Comparison(R.string.versus_ads, Mark.Yes, R.string.versus_ads_ours, Mark.No, R.string.versus_ads_theirs),
    Comparison(R.string.versus_watermark, Mark.Yes, R.string.versus_watermark_ours, Mark.No, R.string.versus_watermark_theirs),
    Comparison(R.string.versus_account, Mark.Yes, R.string.versus_account_ours, Mark.No, R.string.versus_account_theirs),
    Comparison(R.string.versus_ocr, Mark.Yes, R.string.versus_free, Mark.Partly, R.string.versus_paid),
    Comparison(R.string.versus_office, Mark.Yes, R.string.versus_free, Mark.Partly, R.string.versus_paid),
    Comparison(R.string.versus_id, Mark.Yes, R.string.versus_free, Mark.Partly, R.string.versus_paid),
    Comparison(R.string.versus_pdf, Mark.Yes, R.string.versus_free, Mark.Partly, R.string.versus_paid),
    Comparison(R.string.versus_clean, Mark.Yes, R.string.versus_free, Mark.Partly, R.string.versus_paid),
    Comparison(R.string.versus_offline, Mark.Yes, R.string.versus_offline_ours, Mark.Partly, R.string.versus_offline_theirs),
    Comparison(R.string.versus_cloud, Mark.No, R.string.versus_cloud_ours, Mark.Yes, R.string.versus_cloud_theirs),
    Comparison(R.string.versus_translate, Mark.Yes, R.string.versus_translate_ours, Mark.Yes, R.string.versus_translate_theirs),
)

/** Oscanner and CamScanner, row by row, as seen in October 2026. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareSheet(onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surfaceContainer) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.versus_title), style = MaterialTheme.typography.headlineSmall, color = colors.onSurface, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.versus_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(vertical = 6.dp)) {
                Spacer(Modifier.weight(1.1f))
                Text("Oscanner", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.onSurface, modifier = Modifier.weight(1f))
                Text("CamScanner", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            for (row in COMPARISON) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Text(stringResource(row.feature), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.onSurface, modifier = Modifier.weight(1.1f).padding(end = 8.dp))
                    Cell(row.oscanner, row.oscannerNote, Modifier.weight(1f))
                    Cell(row.camscanner, row.camscannerNote, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.versus_footnote), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun Cell(mark: Mark, note: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val extras = LocalOscannerExtras.current
    val (icon, tint) = when (mark) {
        Mark.Yes -> Icons.Rounded.Check to extras.success
        Mark.No -> Icons.Rounded.Close to colors.error
        Mark.Partly -> Icons.Rounded.Remove to colors.onSurfaceVariant
    }
    Row(modifier.padding(end = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
    }
}

private val GAP = 12.dp
/** Picture heights: the two short tiles, the wide one, the photo; the tall one fills its row, at least this. */
private val SHORT = 116.dp
private val WIDE = 176.dp
private val SQUARE = 168.dp
private val TALL_MIN = 200.dp
/** Either side of the wipe line, so it shows on white paper as on a photo. */
private val KEYLINE = Color(0x99161412)
/** The receipt's own paper, around its result when the tile is taller than the receipt. */
private val RECEIPT_PAPER = Color(0xFFFFFFFF)
/** The wipe line and the grip: paper white, as the camera's page outline is. */
private val PAPER = Color(0xFFF4EEE6)
private const val HOLD_MS = 1600
private const val SWEEP_MS = 900
private const val CYCLE_MS = 5000
private const val STAGGER_MS = 700
private const val WAVE_MS = 1400
