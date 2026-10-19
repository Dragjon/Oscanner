package dev.olaf.oscanner.feature.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.design.Dock
import dev.olaf.oscanner.core.design.DockAction
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.model.Page
import dev.olaf.oscanner.core.model.PageFilter
import kotlinx.coroutines.launch

@Composable
fun ReviewRoute(
    viewModel: ReviewViewModel,
    startPage: Int,
    onBack: () -> Unit,
    onSaved: (documentId: Long) -> Unit,
) {
    val allPages by viewModel.pages.collectAsStateWithLifecycle()
    val crop by viewModel.crop.collectAsStateWithLifecycle()
    var pendingDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedText = stringResource(R.string.review_page_deleted)
    val undoText = stringResource(R.string.review_undo)

    val loaded = allPages ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    // A page waiting out its Undo window is hidden but not yet gone.
    val pages = loaded.filter { it.id != pendingDelete }
    if (pages.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val draft = crop
    val cropping = draft?.let { d -> pages.firstOrNull { it.id == d.pageId } }
    if (draft != null && cropping != null) {
        CropMode(viewModel, cropping, draft)
        return
    }

    val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, pages.lastIndex)) { pages.size }
    val current = pages[pagerState.currentPage.coerceIn(0, pages.lastIndex)]
    var adjusting by rememberSaveable { mutableStateOf(false) }
    if (adjusting) {
        AdjustSheet(page = current, onChange = viewModel::setAdjustments, onDismiss = { adjusting = false })
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.review_back))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.review_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.review_page_of, pagerState.currentPage + 1, pages.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pages.size > 1) {
                    TextButton(onClick = {
                        val page = current
                        pendingDelete?.let { previous -> loaded.firstOrNull { it.id == previous }?.let(viewModel::deletePage) }
                        pendingDelete = page.id
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            val result = snackbar.showSnackbar(deletedText, actionLabel = undoText, duration = SnackbarDuration.Short)
                            if (pendingDelete == page.id) {
                                if (result == SnackbarResult.Dismissed) viewModel.deletePage(page)
                                pendingDelete = null
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.review_delete_page), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                key = { pages[it].id },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                pageSpacing = 16.dp,
            ) { index ->
                PagePreview(viewModel, pages[index], Modifier.fillMaxSize())
            }

            PageTicks(count = pages.size, current = pagerState.currentPage)
            Spacer(Modifier.height(12.dp))
            FilterStrip(viewModel, current)
            Row(
                Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { adjusting = true }) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.review_adjust))
                }
                if (pages.size > 1) {
                    TextButton(onClick = { viewModel.applyFilterToAll(current.edits.filter) }) {
                        Text(stringResource(R.string.review_use_for_all))
                    }
                }
            }
            Dock(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(),
            ) {
                DockAction(Icons.Rounded.Crop, stringResource(R.string.review_crop), { viewModel.startCrop(current) })
                DockAction(Icons.Rounded.RotateRight, stringResource(R.string.review_rotate), { viewModel.rotate(current) })
                PrimaryPill(Icons.Rounded.Check, stringResource(R.string.review_save), {
                    pendingDelete?.let { id -> loaded.firstOrNull { it.id == id }?.let(viewModel::deletePage) }
                    viewModel.finishEditing()
                    onSaved(viewModel.documentId)
                })
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        )
    }
}

@Composable
private fun PagePreview(viewModel: ReviewViewModel, page: Page, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null, page.id, page.edits) {
        value = runCatching { viewModel.preview(page).asImageBitmap() }.getOrNull()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val shown = image
        if (shown == null) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Image(
                shown,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.clip(MaterialTheme.shapes.small),
            )
        }
    }
}

@Composable
private fun PageTicks(count: Int, current: Int) {
    if (count < 2) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count.coerceAtMost(24)) { i ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = if (i == current) 16.dp else 6.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(if (i == current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** Live thumbnails of this page in every look; the chosen one is outlined in orange. */
@Composable
private fun FilterStrip(viewModel: ReviewViewModel, page: Page) {
    val previews by produceState<Map<PageFilter, ImageBitmap>?>(null, page.id, page.edits.quad, page.edits.rotation) {
        value = runCatching { viewModel.filterPreviews(page).mapValues { it.value.asImageBitmap() } }.getOrNull()
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(PageFilter.entries) { filter ->
            val selected = filter == page.edits.filter
            val colors = MaterialTheme.colorScheme
            Surface(
                onClick = { viewModel.setFilter(page, filter) },
                shape = MaterialTheme.shapes.medium,
                color = if (selected) colors.primaryContainer else colors.surfaceContainer,
                border = if (selected) BorderStroke(2.dp, colors.primary) else null,
                modifier = Modifier.semantics {
                    role = Role.RadioButton
                    this.selected = selected
                },
            ) {
                Column(
                    Modifier.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(width = 64.dp, height = 80.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(colors.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        previews?.get(filter)?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(filter.label()),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CropMode(viewModel: ReviewViewModel, page: Page, draft: CropDraft) {
    val original by produceState<ImageBitmap?>(null, page.id) {
        value = runCatching { viewModel.original(page).asImageBitmap() }.getOrNull()
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Text(
            stringResource(R.string.review_crop_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val image = original
            if (image == null) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CropEditor(image, draft.quad, viewModel::moveCorner, Modifier.fillMaxSize())
            }
        }
        Dock(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .navigationBarsPadding()
                .padding(16.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
        ) {
            DockAction(Icons.Rounded.Close, stringResource(R.string.review_cancel), viewModel::cancelCrop)
            DockAction(Icons.Rounded.CropFree, stringResource(R.string.review_whole_photo), viewModel::cropWholePhoto)
            if (draft.valid) {
                PrimaryPill(Icons.Rounded.Check, stringResource(R.string.review_apply), viewModel::applyCrop)
            } else {
                DockAction(Icons.Rounded.Check, stringResource(R.string.review_apply), {}, enabled = false)
            }
        }
    }
    androidx.activity.compose.BackHandler(onBack = viewModel::cancelCrop)
}

internal fun PageFilter.label(): Int = when (this) {
    PageFilter.Original -> R.string.review_filter_original
    PageFilter.Auto -> R.string.review_filter_auto
    PageFilter.Bright -> R.string.review_filter_bright
    PageFilter.Gray -> R.string.review_filter_gray
    PageFilter.Ink -> R.string.review_filter_ink
}
