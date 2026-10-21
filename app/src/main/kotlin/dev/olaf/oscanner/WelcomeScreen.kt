package dev.olaf.oscanner

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.design.ChalkSnowman
import dev.olaf.oscanner.core.design.PrimaryPill
import kotlinx.coroutines.launch

/**
 * The tour on first opening: what Oscanner does, a page at a time, and how it stands against
 * CamScanner. Skip or Start ends it; it can be seen again from Settings.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val pages = 6
    val pager = rememberPagerState { pages }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val last = pager.currentPage == pages - 1

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
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
            when (page) {
                0 -> Hello()
                1 -> Feature(
                    Icons.Rounded.DocumentScanner,
                    R.string.welcome_scan_title,
                    listOf(R.string.welcome_scan_1, R.string.welcome_scan_2, R.string.welcome_scan_3, R.string.welcome_scan_4, R.string.welcome_scan_5),
                )
                2 -> Feature(
                    Icons.Rounded.AutoAwesome,
                    R.string.welcome_clean_title,
                    listOf(R.string.welcome_clean_1, R.string.welcome_clean_2, R.string.welcome_clean_3, R.string.welcome_clean_4, R.string.welcome_clean_5),
                )
                3 -> Feature(
                    Icons.Rounded.Description,
                    R.string.welcome_text_title,
                    listOf(R.string.welcome_text_1, R.string.welcome_text_2, R.string.welcome_text_3, R.string.welcome_text_4, R.string.welcome_text_5),
                )
                4 -> Feature(
                    Icons.Rounded.Lock,
                    R.string.welcome_private_title,
                    listOf(R.string.welcome_private_1, R.string.welcome_private_2, R.string.welcome_private_3, R.string.welcome_private_4),
                )
                else -> Versus()
            }
        }
        // Where you are in the tour.
        Row(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 12.dp)
                .semantics { contentDescription = "${pager.currentPage + 1} / $pages" },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(pages) { i ->
                Box(
                    Modifier
                        .size(if (i == pager.currentPage) 10.dp else 8.dp)
                        .background(if (i == pager.currentPage) colors.primary else colors.outlineVariant, CircleShape),
                )
            }
        }
        PrimaryPill(
            icon = if (last) Icons.Rounded.DocumentScanner else Icons.AutoMirrored.Rounded.ArrowForward,
            label = stringResource(if (last) R.string.welcome_start else R.string.welcome_next),
            onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 20.dp),
        )
    }
}

@Composable
private fun Hello() {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ChalkSnowman(Modifier.size(width = 120.dp, height = 140.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.welcome_hello_title),
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.welcome_hello_body),
            style = MaterialTheme.typography.titleMedium,
            color = colors.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.welcome_hello_rant),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, points: List<Int>) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = colors.primary.copy(alpha = 0.15f), contentColor = colors.primary, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp)) }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(16.dp))
        for (point in points) {
            Row(Modifier.padding(vertical = 6.dp)) {
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
private fun Versus() {
    val colors = MaterialTheme.colorScheme
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
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.versus_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1.1f))
                    Text("Oscanner", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.primary, modifier = Modifier.weight(1f))
                    Text("CamScanner", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
                for (row in COMPARISON) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        Text(stringResource(row.feature), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.onSurface, modifier = Modifier.weight(1.1f).padding(end = 8.dp))
                        Cell(row.oscanner, row.oscannerNote, Modifier.weight(1f))
                        Cell(row.camscanner, row.camscannerNote, Modifier.weight(1f))
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
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Cell(mark: Mark, note: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val (icon, tint) = when (mark) {
        Mark.Yes -> Icons.Rounded.Check to colors.primary
        Mark.No -> Icons.Rounded.Close to colors.error
        Mark.Partly -> Icons.Rounded.Remove to colors.onSurfaceVariant
    }
    Row(modifier.padding(end = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
    }
}
