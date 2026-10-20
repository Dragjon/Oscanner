package dev.olaf.oscanner.feature.capture

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.data.CapturedImage
import dev.olaf.oscanner.core.design.CropEditor
import dev.olaf.oscanner.core.design.Dock
import dev.olaf.oscanner.core.design.DockAction
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.design.rememberPageImage
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
import java.io.File

/**
 * The pages taken so far, over the camera, mid-scan: swipe between them, drag a page's corners
 * to fix its outline, or drop it. Keep scanning goes back to the camera with the batch intact;
 * looks and turning come after Done, in Review.
 */
@Composable
internal fun CapturedPages(
    captures: List<CapturedImage>,
    onOutline: (File, Quad) -> Unit,
    onDelete: (File) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    // Opens on the newest page: the one just taken is the one most likely to need a fix.
    val pager = rememberPagerState(initialPage = captures.lastIndex) { captures.size }
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.capture_pages_back))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.capture_pages_title, pager.currentPage + 1, captures.size),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.capture_pages_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        HorizontalPager(
            state = pager,
            key = { captures[it].file.path },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            pageSpacing = 16.dp,
        ) { index ->
            val capture = captures[index]
            Page(capture, onMoveCorner = { corner, x, y ->
                val quad = capture.quad ?: capture.liveQuad ?: Quad.Full
                onOutline(
                    capture.file,
                    Quad.of(quad.corners.mapIndexed { i, c -> if (i == corner) Corner(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) else c }),
                )
            })
        }

        Dock(
            Modifier
                .align(Alignment.CenterHorizontally)
                .navigationBarsPadding()
                .padding(16.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
        ) {
            val current = captures.getOrNull(pager.currentPage)
            DockAction(Icons.Rounded.CropFree, stringResource(R.string.capture_pages_whole), {
                current?.let { onOutline(it.file, Quad.Full) }
            })
            PrimaryPill(Icons.Rounded.CameraAlt, stringResource(R.string.capture_pages_keep_scanning), onClose)
            DockAction(Icons.Rounded.DeleteOutline, stringResource(R.string.capture_pages_delete), {
                current?.let { onDelete(it.file) }
            })
        }
    }
}

@Composable
private fun Page(capture: CapturedImage, onMoveCorner: (Int, Float, Float) -> Unit) {
    val image by rememberPageImage(capture.file.path, maxSide = 1600)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val shown = image
        if (shown == null) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            CropEditor(shown, capture.quad ?: capture.liveQuad ?: Quad.Full, onMoveCorner, Modifier.fillMaxSize())
        }
    }
}
