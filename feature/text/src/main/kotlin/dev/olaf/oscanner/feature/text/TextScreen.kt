package dev.olaf.oscanner.feature.text

import dev.olaf.oscanner.core.design.endTypingOutside
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import dev.olaf.oscanner.core.design.ChalkSnowman
import dev.olaf.oscanner.core.data.ReadProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.design.Dock
import dev.olaf.oscanner.core.design.DockAction
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.design.SheetAction
import dev.olaf.oscanner.core.design.rememberPageImage
import dev.olaf.oscanner.core.design.share
import dev.olaf.oscanner.core.model.Page
import dev.olaf.oscanner.core.model.ProcessingState
import kotlinx.coroutines.launch

/** The words read from each page, under a small view of the page itself (stacked on phones). */
@Composable
fun TextRoute(viewModel: TextViewModel, startPage: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.text_copied)
    val noApp = stringResource(R.string.text_no_app)
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var exporting by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.exportedFiles.collect { (file, type) -> if (!context.share(listOf(file), type)) snackbar.showSnackbar(noApp) }
    }

    val pages = state.pages
    if (state.loading || pages.isEmpty()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, pages.lastIndex)) { pages.size }
    val current = pages[pagerState.currentPage.coerceIn(0, pages.lastIndex)]
    val reading = state.progress != null || state.document?.state == ProcessingState.ReadingText

    fun copy(text: String) {
        context.copyToClipboard(text)
        // Android 13+ shows its own confirmation for clipboard writes.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .endTypingOutside(),
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
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.text_back))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.text_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.text_page_of, pagerState.currentPage + 1, pages.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!current.text.isNullOrBlank()) {
                    val editing = editingId == current.id
                    TextButton(onClick = { editingId = if (editing) null else current.id }) {
                        if (!editing) {
                            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(stringResource(if (editing) R.string.text_done else R.string.text_edit))
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                key = { pages[it].id },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                pageSpacing = 16.dp,
            ) { index ->
                val page = pages[index]
                PageText(
                    page = page,
                    reading = reading,
                    progress = state.progress,
                    editing = editingId == page.id,
                    onReadNow = viewModel::readNow,
                    onSave = { viewModel.save(page, it) },
                )
            }

            Dock(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(),
            ) {
                DockAction(Icons.Rounded.LibraryBooks, stringResource(R.string.text_copy_all), { copy(viewModel.allText()) })
                PrimaryPill(Icons.Rounded.ContentCopy, stringResource(R.string.text_copy), { copy(current.text.orEmpty()) })
                DockAction(Icons.Rounded.FileDownload, stringResource(R.string.text_export), { exporting = true })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 104.dp))
    }

    if (exporting) {
        ExportSheet(
            onWord = {
                exporting = false
                viewModel.exportWord()
            },
            onPlain = {
                exporting = false
                viewModel.exportText()
            },
            onSpreadsheet = {
                exporting = false
                viewModel.exportSpreadsheet()
            },
            onDismiss = { exporting = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(onWord: () -> Unit, onPlain: () -> Unit, onSpreadsheet: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetAction(Icons.Rounded.Description, stringResource(R.string.text_export_word), stringResource(R.string.text_export_word_description), onWord)
            SheetAction(Icons.Rounded.TableChart, stringResource(R.string.text_export_sheet), stringResource(R.string.text_export_sheet_description), onSpreadsheet)
            SheetAction(Icons.Rounded.TextSnippet, stringResource(R.string.text_export_plain), stringResource(R.string.text_export_plain_description), onPlain)
        }
    }
}

/**
 * Reading in progress: the chalk snowman bobs while a bar fills with how far the reader has got,
 * page by page and line by line.
 */
@Composable
private fun ReadingProgressView(progress: ReadProgress?) {
    val colors = MaterialTheme.colorScheme
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val shown by animateFloatAsState(progress?.fraction ?: 0f, tween(400), label = "progress")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChalkSnowman(
            Modifier
                .size(width = 140.dp, height = 154.dp)
                .graphicsLayer { translationY = bob.dp.toPx() },
        )
        Spacer(Modifier.height(12.dp))
        val label = when {
            progress == null || progress.preparing || progress.pages == 0 -> stringResource(R.string.text_reading_starting)
            progress.pages == 1 -> stringResource(R.string.text_reading_percent, (shown * 100).toInt())
            else -> stringResource(R.string.text_reading_pages, progress.page + 1, progress.pages, (shown * 100).toInt())
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        Spacer(Modifier.height(10.dp))
        if (progress == null || progress.preparing || progress.pages == 0) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(0.7f).height(6.dp).clip(CircleShape),
                color = colors.primary,
                trackColor = colors.surfaceContainerHigh,
            )
        } else {
            LinearProgressIndicator(
                progress = { shown },
                modifier = Modifier.fillMaxWidth(0.7f).height(6.dp).clip(CircleShape),
                color = colors.primary,
                trackColor = colors.surfaceContainerHigh,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun PageText(
    page: Page,
    reading: Boolean,
    progress: ReadProgress?,
    editing: Boolean,
    onReadNow: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val image by rememberPageImage(page.thumbPath ?: page.renderPath, maxSide = 400)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = colors.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                image?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.padding(12.dp).clip(MaterialTheme.shapes.small))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val text = page.text
        when {
            text == null && reading -> ReadingProgressView(progress)
            text == null -> Column {
                Text(stringResource(R.string.text_not_read), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                PrimaryPill(Icons.Rounded.LibraryBooks, stringResource(R.string.text_read_now), onReadNow)
            }
            text.isBlank() -> Text(stringResource(R.string.text_none), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            editing -> {
                var draft by rememberSaveable(page.id) { mutableStateOf(text) }
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        onSave(it)
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.onSurfaceVariant,
                        unfocusedBorderColor = colors.outlineVariant,
                        cursorColor = colors.primary,
                    ),
                )
            }
            else -> SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Oscanner", text))
}
