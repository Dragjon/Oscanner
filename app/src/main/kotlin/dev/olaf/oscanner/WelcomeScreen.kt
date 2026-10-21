package dev.olaf.oscanner

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.design.ChalkSnowman
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.design.rememberReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The tour on first opening: what Oscanner does, a page at a time, and how it stands against
 * CamScanner. Snow falls through it and drifts as the pages turn; Olaf walks from the greeting
 * down to the corner and hops along from page to page; each page's lines are chalked in one
 * after another. With animations off in system settings, it's all still. Skip or Start ends it;
 * it can be seen again from Settings.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val pages = 6
    val pager = rememberPagerState { pages }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val last = pager.currentPage == pages - 1
    // Where Olaf stands: in the greeting, and in the corner by the button.
    var greetingSpot by remember { mutableStateOf<Rect?>(null) }
    var cornerSpot by remember { mutableStateOf<Rect?>(null) }
    // 0 → 1 while he celebrates the start; the snow flurries with him.
    val cheer = remember { Animatable(0f) }
    val start: () -> Unit = {
        if (reduced) {
            onDone()
        } else if (!cheer.isRunning) {
            scope.launch {
                cheer.animateTo(1f, tween(CHEER_MS, easing = FastOutSlowInEasing))
                onDone()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Snowfall(pager, cheer.value, reduced, Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                if (!last) {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.welcome_skip), color = colors.onSurfaceVariant) }
                } else {
                    Spacer(Modifier.height(48.dp))
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                // How far this page is from the middle: 0 when it's the one shown, ±1 a page away.
                val offset = { (pager.currentPage - page) + pager.currentPageOffsetFraction }
                val shown = pager.settledPage == page
                when (page) {
                    0 -> Hello(offset, onSpot = { greetingSpot = it })
                    1 -> Feature(
                        Icons.Rounded.DocumentScanner,
                        R.string.welcome_scan_title,
                        listOf(R.string.welcome_scan_1, R.string.welcome_scan_2, R.string.welcome_scan_3, R.string.welcome_scan_4, R.string.welcome_scan_5),
                        offset, shown, reduced,
                    )
                    2 -> Feature(
                        Icons.Rounded.AutoAwesome,
                        R.string.welcome_clean_title,
                        listOf(R.string.welcome_clean_1, R.string.welcome_clean_2, R.string.welcome_clean_3, R.string.welcome_clean_4, R.string.welcome_clean_5),
                        offset, shown, reduced,
                    )
                    3 -> Feature(
                        Icons.Rounded.Description,
                        R.string.welcome_text_title,
                        listOf(R.string.welcome_text_1, R.string.welcome_text_2, R.string.welcome_text_3, R.string.welcome_text_4, R.string.welcome_text_5),
                        offset, shown, reduced,
                    )
                    4 -> Feature(
                        Icons.Rounded.Lock,
                        R.string.welcome_private_title,
                        listOf(R.string.welcome_private_1, R.string.welcome_private_2, R.string.welcome_private_3, R.string.welcome_private_4),
                        offset, shown, reduced,
                    )
                    else -> Versus(offset, shown, reduced)
                }
            }
            // Where you are in the tour: the current dot stretches.
            Row(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 12.dp)
                    .semantics { contentDescription = "${pager.currentPage + 1} / $pages" },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pages) { i ->
                    val width by animateDpAsState(if (i == pager.currentPage) 22.dp else 8.dp, spring(dampingRatio = 0.7f), label = "dot")
                    Box(
                        Modifier
                            .size(width = width, height = 8.dp)
                            .background(if (i == pager.currentPage) colors.primary else colors.outlineVariant, CircleShape),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                // Olaf's spot in the corner, left of the button.
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 20.dp)
                        .size(width = 56.dp, height = 64.dp)
                        .onGloballyPositioned { cornerSpot = it.boundsInRoot() },
                )
                PrimaryPill(
                    icon = if (last) Icons.Rounded.DocumentScanner else Icons.AutoMirrored.Rounded.ArrowForward,
                    label = stringResource(if (last) R.string.welcome_start else R.string.welcome_next),
                    onClick = { if (last) start() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        TravellingOlaf(pager, greetingSpot, cornerSpot, cheer.value, reduced)
    }
}

/**
 * One snowman for the whole tour: big in the greeting, then down to the corner, hopping from
 * page to page with the swipe and leaning the way it goes, bobbing while it waits.
 */
@Composable
private fun TravellingOlaf(pager: PagerState, greeting: Rect?, corner: Rect?, cheer: Float, reduced: Boolean) {
    val greet = greeting ?: return
    val rest = corner ?: return
    val density = LocalDensity.current
    val bob by produceBob(reduced)
    val position = pager.currentPage + pager.currentPageOffsetFraction
    val from = floor(position).toInt().coerceIn(0, pager.pageCount - 1)
    val t = (position - from).coerceIn(0f, 1f)
    fun spot(page: Int) = if (page == 0) greet else rest
    val a = spot(from)
    val b = spot((from + 1).coerceAtMost(pager.pageCount - 1))
    fun lerp(x: Float, y: Float) = x + (y - x) * t
    val rect = Rect(lerp(a.left, b.left), lerp(a.top, b.top), lerp(a.right, b.right), lerp(a.bottom, b.bottom))
    val hopPx = with(density) { HOP.toPx() }
    // Between two corner pages it hops; off the greeting it glides down, with a small hop too.
    val hop = if (reduced) 0f else sin(PI * t).toFloat() * hopPx * (if (from == 0) 0.4f else 1f)
    val lean = if (reduced) 0f else sin(PI * t).toFloat() * 12f * if (pager.currentPageOffsetFraction >= 0f) 1f else -1f
    val cheerHop = sin(PI * cheer).toFloat() * hopPx * 2.2f
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), (rect.top - hop - cheerHop + bob * density.density).roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .graphicsLayer {
                rotationZ = lean + cheer * 360f
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            },
    ) {
        ChalkSnowman(Modifier.fillMaxSize())
    }
}

/** A gentle up and down, in dp, while nothing else moves him. */
@Composable
private fun produceBob(reduced: Boolean) = androidx.compose.runtime.produceState(0f, reduced) {
    if (reduced) return@produceState
    val start = withFrameMillis { it }
    while (true) {
        withFrameMillis { now -> value = sin((now - start) / 600.0).toFloat() * -3f }
    }
}

private class Flake(val x: Float, val y: Float, val speed: Float, val radius: Float, val depth: Float, val sway: Float)

/**
 * Chalk snow behind the tour. Nearer flakes are bigger, fall faster and drift further as the
 * pages turn (parallax); during the cheer they all hurry.
 */
@Composable
private fun Snowfall(pager: PagerState, cheer: Float, reduced: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val chalk = if (dark) Color(0xFFEDE6DC) else Color(0xFF8A7F76)
    val flakes = remember {
        val r = Random(7)
        List(FLAKES) {
            val depth = r.nextFloat()
            Flake(r.nextFloat(), r.nextFloat(), 0.02f + 0.05f * depth, 1.2f + 2.6f * depth, depth, r.nextFloat() * 6.28f)
        }
    }
    var time by remember { mutableStateOf(0f) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) {
            withFrameMillis { now ->
                time += (now - last) / 1000f
                last = now
            }
        }
    }
    val rush = 1f + 5f * sin(PI * cheer).toFloat()
    val position = pager.currentPage + pager.currentPageOffsetFraction
    Canvas(modifier) {
        for (f in flakes) {
            val fall = (f.y + time * f.speed * rush) % 1.1f - 0.05f
            val drift = sin(time * 0.6f + f.sway) * 0.015f - position * (0.04f + 0.12f * f.depth)
            val x = ((f.x + drift) % 1f + 1f) % 1f
            drawCircle(chalk.copy(alpha = 0.18f + 0.32f * f.depth), f.radius * density, Offset(x * size.width, fall * size.height))
        }
    }
}

/** Chalks [count] lines in, one after another, the first time a page is shown; 0 → 1 each. */
@Composable
private fun rememberChalkIn(count: Int, shown: Boolean, reduced: Boolean): List<Float> {
    val lines = remember { List(count) { Animatable(if (reduced) 1f else 0f) } }
    LaunchedEffect(shown) {
        if (!shown || reduced) return@LaunchedEffect
        lines.forEachIndexed { i, line ->
            launch {
                delay(STAGGER_MS * i)
                line.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow))
            }
        }
    }
    return lines.map { it.value }
}

/** A line chalked in: rises a little and fades up. */
private fun Modifier.chalked(progress: Float) = graphicsLayer {
    alpha = progress
    translationY = (1f - progress) * 24f * density
}

/** Slides with the page, at its own pace: [depth] above 1 runs ahead, below 1 lags. */
private fun Modifier.parallax(offset: () -> Float, depth: Float) = graphicsLayer {
    val o = offset()
    translationX = o * size.width * (depth - 1f)
    alpha = 1f - abs(o).coerceAtMost(1f) * 0.6f
}

@Composable
private fun Hello(offset: () -> Float, onSpot: (Rect) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Olaf stands here (drawn on top, so he can walk off to the corner).
        Spacer(Modifier.size(width = 120.dp, height = 140.dp).onGloballyPositioned { onSpot(it.boundsInRoot()) })
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.welcome_hello_title),
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.parallax(offset, 1.3f).semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.welcome_hello_body),
            style = MaterialTheme.typography.titleMedium,
            color = colors.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.parallax(offset, 1.15f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.welcome_hello_rant),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.parallax(offset, 1f),
        )
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, points: List<Int>, offset: () -> Float, shown: Boolean, reduced: Boolean) {
    val colors = MaterialTheme.colorScheme
    val lines = rememberChalkIn(points.size, shown, reduced)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = colors.primary.copy(alpha = 0.15f),
            contentColor = colors.primary,
            modifier = Modifier.size(64.dp).parallax(offset, 1.8f),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp)) }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onBackground,
            modifier = Modifier.parallax(offset, 1.3f).semantics { heading() },
        )
        Spacer(Modifier.height(16.dp))
        points.forEachIndexed { i, point ->
            Row(Modifier.padding(vertical = 6.dp).parallax(offset, 1f).chalked(lines[i])) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(point), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
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
    Comparison(R.string.versus_translate, Mark.No, R.string.versus_translate_ours, Mark.Yes, R.string.versus_translate_theirs),
)

@Composable
private fun Versus(offset: () -> Float, shown: Boolean, reduced: Boolean) {
    val colors = MaterialTheme.colorScheme
    val rows = rememberChalkIn(COMPARISON.size + 1, shown, reduced)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.versus_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onBackground,
            modifier = Modifier.parallax(offset, 1.3f).semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.versus_body),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.parallax(offset, 1.15f),
        )
        Spacer(Modifier.height(16.dp))
        Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainer, modifier = Modifier.parallax(offset, 1f)) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1.1f))
                    Text("Oscanner", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.primary, modifier = Modifier.weight(1f))
                    Text("CamScanner", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                COMPARISON.forEachIndexed { i, row ->
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp).chalked(rows[i]), verticalAlignment = Alignment.Top) {
                        Text(stringResource(row.feature), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.onSurface, modifier = Modifier.weight(1.1f).padding(end = 8.dp))
                        Cell(row.oscanner, row.oscannerNote, rows[i], Modifier.weight(1f))
                        Cell(row.camscanner, row.camscannerNote, rows[i], Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.versus_footnote), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.versus_verdict),
            style = MaterialTheme.typography.titleMedium,
            color = colors.primary,
            modifier = Modifier.chalked(rows.last()),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Cell(mark: Mark, note: Int, progress: Float, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val (icon, tint) = when (mark) {
        Mark.Yes -> Icons.Rounded.Check to colors.primary
        Mark.No -> Icons.Rounded.Close to colors.error
        Mark.Partly -> Icons.Rounded.Remove to colors.onSurfaceVariant
    }
    // A cross is stamped on: it lands big and settles, after the row has chalked in.
    val stamp = if (mark == Mark.No) 1f + 0.8f * (1f - ((progress - 0.5f) * 2f).coerceIn(0f, 1f)) else 1f
    Row(modifier.padding(end = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(18.dp)
                .padding(top = 2.dp)
                .graphicsLayer {
                    scaleX = stamp
                    scaleY = stamp
                },
        )
        Spacer(Modifier.width(4.dp))
        Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
    }
}

private const val FLAKES = 70
private const val STAGGER_MS = 90L
private const val CHEER_MS = 900
private val HOP = 28.dp
