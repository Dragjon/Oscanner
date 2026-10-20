package dev.olaf.oscanner.feature.document

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Rectangle
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.design.Dock
import dev.olaf.oscanner.core.design.DockAction
import dev.olaf.oscanner.core.design.SheetAction
import dev.olaf.oscanner.core.design.StatusChip
import dev.olaf.oscanner.core.design.printPdf
import dev.olaf.oscanner.core.design.rememberPageImage
import dev.olaf.oscanner.core.design.share
import dev.olaf.oscanner.core.model.Folder
import dev.olaf.oscanner.core.model.Page
import dev.olaf.oscanner.core.model.ProcessingState

private enum class Sheet { Edit, Share, Document }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentRoute(
    viewModel: DocumentViewModel,
    onBack: () -> Unit,
    onEdit: (documentId: Long, page: Int, look: Boolean) -> Unit,
    onText: (documentId: Long, page: Int) -> Unit,
    onScanMore: (documentId: Long) -> Unit,
    onArrange: (documentId: Long) -> Unit,
    onMarkup: (documentId: Long, page: Int) -> Unit,
    onCleanup: (documentId: Long, page: Int) -> Unit,
    onRedact: (documentId: Long, page: Int) -> Unit,
    onTrashed: (documentId: Long) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf(false) }
    var protecting by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }

    val savedText = stringResource(R.string.document_saved)
    val failedText = stringResource(R.string.document_failed)
    val noAppText = stringResource(R.string.document_no_share_app)
    val currentOnTrashed by rememberUpdatedState(onTrashed)
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is DocumentEvent.SharePdf -> if (!context.share(listOf(event.file), "application/pdf")) snackbar.showSnackbar(noAppText)
                is DocumentEvent.ShareImages -> if (!context.share(event.files, "image/jpeg")) snackbar.showSnackbar(noAppText)
                is DocumentEvent.Print -> context.printPdf(event.file, event.title)
                DocumentEvent.Saved -> snackbar.showSnackbar(savedText)
                DocumentEvent.Failed -> snackbar.showSnackbar(failedText)
                is DocumentEvent.Trashed -> currentOnTrashed(event.documentId)
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { viewModel.savePdfTo(it) { target -> context.contentResolver.openOutputStream(target) } }
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        viewModel.importPages(uris)
    }

    val document = state.document
    if (document == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        // Deleted forever from elsewhere, or a stale link.
        if (!state.loading) LaunchedEffect(Unit) { onBack() }
        return
    }

    val pagerState = rememberPagerState { state.pages.size }
    val colors = MaterialTheme.colorScheme

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
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
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.document_back))
                }
                Column(Modifier.weight(1f)) {
                    // The name itself is the rename button; the pencil says so.
                    Row(
                        Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClickLabel = stringResource(R.string.document_rename)) { renaming = true }
                            .padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            document.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.Edit, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    val size = Formatter.formatShortFileSize(context, document.sizeBytes)
                    val pages = pluralStringResource(dev.olaf.oscanner.core.design.R.plurals.design_pages, document.pageCount, document.pageCount)
                    Text("$pages · $size", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                if (document.state != ProcessingState.Idle) {
                    StatusChip(document.state, Modifier.padding(end = 8.dp))
                }
            }

            HorizontalPager(
                state = pagerState,
                key = { state.pages[it].id },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                pageSpacing = 16.dp,
            ) { index ->
                PageView(state.pages[index], Modifier.fillMaxSize())
            }

            if (state.pages.size > 1) {
                Text(
                    stringResource(R.string.document_page_of, pagerState.currentPage + 1, state.pages.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp),
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                state.busy?.let { BusyLine(it) }
                Dock(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                    // One button per kind of thing you do with a document: change it, send it out,
                    // read its words, or look after the document itself.
                    DockAction(Icons.Rounded.Edit, stringResource(R.string.document_edit), { sheet = Sheet.Edit })
                    DockAction(Icons.Rounded.TextSnippet, stringResource(R.string.document_text), { onText(document.id, pagerState.currentPage) })
                    DockAction(Icons.Rounded.Info, stringResource(R.string.document_info), { sheet = Sheet.Document })
                    DockAction(Icons.Rounded.Share, stringResource(R.string.document_share), { sheet = Sheet.Share })
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 104.dp),
        )
    }

    sheet?.let { open ->
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surfaceContainer,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                fun act(block: () -> Unit) = {
                    sheet = null
                    block()
                }
                when (open) {
                    Sheet.Edit -> {
                        SheetSection(stringResource(R.string.document_section_this_page))
                        SheetAction(Icons.Rounded.Crop, stringResource(R.string.document_crop), stringResource(R.string.document_crop_description), act { onEdit(document.id, pagerState.currentPage, false) })
                        SheetAction(Icons.Rounded.AutoAwesome, stringResource(R.string.document_look), stringResource(R.string.document_look_description), act { onEdit(document.id, pagerState.currentPage, true) })
                        SheetAction(Icons.Rounded.CleaningServices, stringResource(R.string.document_cleanup), stringResource(R.string.document_cleanup_description), act { onCleanup(document.id, pagerState.currentPage) })
                        SheetAction(Icons.Rounded.Draw, stringResource(R.string.document_markup), stringResource(R.string.document_markup_description), act { onMarkup(document.id, pagerState.currentPage) })
                        SheetAction(Icons.Rounded.Rectangle, stringResource(R.string.document_redact), stringResource(R.string.document_redact_description), act { onRedact(document.id, pagerState.currentPage) })
                        SheetSection(stringResource(R.string.document_section_pages))
                        SheetAction(Icons.Rounded.GridView, stringResource(R.string.document_arrange), stringResource(R.string.document_arrange_description), act { onArrange(document.id) })
                        SheetAction(Icons.Rounded.DocumentScanner, stringResource(R.string.document_scan_more), stringResource(R.string.document_scan_more_description), act { onScanMore(document.id) })
                        SheetAction(
                            Icons.Rounded.AddPhotoAlternate,
                            stringResource(R.string.document_import_more),
                            stringResource(R.string.document_import_more_description),
                            act { pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        )
                    }
                    Sheet.Share -> {
                        SheetSection(stringResource(R.string.document_section_send))
                        SheetAction(Icons.Rounded.PictureAsPdf, stringResource(R.string.document_share_pdf), stringResource(R.string.document_share_pdf_description), act { viewModel.sharePdf() })
                        SheetAction(Icons.Rounded.Lock, stringResource(R.string.document_share_protected), stringResource(R.string.document_share_protected_description), act { protecting = true })
                        SheetAction(Icons.Rounded.Image, stringResource(R.string.document_share_images), stringResource(R.string.document_share_images_description), act(viewModel::shareImages))
                        SheetSection(stringResource(R.string.document_section_export))
                        SheetAction(Icons.Rounded.SaveAlt, stringResource(R.string.document_save_pdf), stringResource(R.string.document_save_pdf_description), act { saveLauncher.launch("${document.title}.pdf") })
                        SheetAction(Icons.Rounded.Print, stringResource(R.string.document_print), stringResource(R.string.document_print_description), act(viewModel::print))
                    }
                    Sheet.Document -> {
                        SheetSection(stringResource(R.string.document_section_about))
                        DocumentFacts(document, state.folders.firstOrNull { it.id == document.folderId }?.name)
                        SheetSection(stringResource(R.string.document_section_organize))
                        SheetAction(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.document_rename), stringResource(R.string.document_rename_description), act { renaming = true })
                        SheetAction(
                            Icons.Rounded.Sell,
                            stringResource(R.string.document_details),
                            detailsSummary(document.tags, document.note) ?: stringResource(R.string.document_details_description),
                            act { details = true },
                        )
                        SheetAction(
                            Icons.Rounded.DriveFileMove,
                            stringResource(R.string.document_move),
                            state.folders.firstOrNull { it.id == document.folderId }?.name ?: stringResource(R.string.document_no_folder),
                            act { moving = true },
                        )
                        SheetAction(Icons.Rounded.DeleteOutline, stringResource(R.string.document_trash), stringResource(R.string.document_trash_description), act(viewModel::moveToTrash))
                    }
                }
            }
        }
    }

    if (renaming) {
        RenameDialog(
            initial = document.title,
            onSave = {
                renaming = false
                viewModel.rename(it)
            },
            onDismiss = { renaming = false },
        )
    }
    if (details) {
        DetailsSheet(
            tags = document.tags,
            note = document.note.orEmpty(),
            allTags = state.allTags,
            onAddTag = viewModel::addTag,
            onRemoveTag = viewModel::removeTag,
            onDismiss = { note ->
                details = false
                if (note.trim() != document.note.orEmpty()) viewModel.setNote(note)
            },
        )
    }
    if (protecting) {
        PasswordDialog(
            onShare = {
                protecting = false
                viewModel.sharePdf(it)
            },
            onDismiss = { protecting = false },
        )
    }
    if (moving) {
        MoveDialog(
            folders = state.folders,
            currentId = document.folderId,
            onMove = {
                moving = false
                viewModel.moveTo(it)
            },
            onDismiss = { moving = false },
        )
    }
}

@Composable
private fun PageView(page: Page, modifier: Modifier = Modifier) {
    // The full render once it exists; until then the small thumbnail or the original photo.
    val path = page.renderPath ?: page.thumbPath ?: page.originalPath
    val image by rememberPageImage(path, maxSide = 2000)
    Box(modifier, contentAlignment = Alignment.Center) {
        val shown = image
        if (shown == null) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Image(shown, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.clip(MaterialTheme.shapes.small))
        }
        if (page.renderPath == null) {
            StatusChip(ProcessingState.Enhancing, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        }
    }
}

/** A small heading that splits a sheet into groups of related actions. */
@Composable
private fun SheetSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp).semantics { heading() },
    )
}

/** Plain facts about the document, read-only: pages, size, folder, when it was made and changed. */
@Composable
private fun DocumentFacts(document: dev.olaf.oscanner.core.model.Document, folder: String?) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val dateFormat = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    val rows = listOf(
        stringResource(R.string.document_fact_pages) to
            pluralStringResource(dev.olaf.oscanner.core.design.R.plurals.design_pages, document.pageCount, document.pageCount),
        stringResource(R.string.document_fact_size) to Formatter.formatShortFileSize(context, document.sizeBytes),
        stringResource(R.string.document_fact_folder) to (folder ?: stringResource(R.string.document_no_folder)),
        stringResource(R.string.document_fact_created) to dateFormat.format(java.util.Date(document.createdAt)),
        stringResource(R.string.document_fact_changed) to dateFormat.format(java.util.Date(document.updatedAt)),
    )
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (label, value) ->
                Row {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(96.dp))
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                }
            }
        }
    }
}

@Composable
private fun BusyLine(busy: Busy) {
    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(
                when (busy) {
                    Busy.PreparingPdf -> R.string.document_preparing_pdf
                    Busy.PreparingImages -> R.string.document_preparing_images
                    Busy.Saving -> R.string.document_saving
                    Busy.Importing -> R.string.document_importing
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(stringResource(R.string.document_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.document_name_hint)) },
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.onSurfaceVariant,
                    unfocusedBorderColor = colors.outlineVariant,
                    cursorColor = colors.primary,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.document_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.document_cancel), color = colors.onSurfaceVariant) }
        },
    )
}

@Composable
private fun MoveDialog(folders: List<Folder>, currentId: Long?, onMove: (Long?) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(stringResource(R.string.document_move)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SheetAction(
                    icon = Icons.Rounded.FolderOff,
                    title = stringResource(R.string.document_no_folder),
                    description = null,
                    onClick = { onMove(null) },
                    trailing = if (currentId == null) ({ CurrentMark() }) else null,
                )
                folders.forEach { folder ->
                    SheetAction(
                        icon = Icons.Rounded.Folder,
                        title = folder.name,
                        description = null,
                        onClick = { onMove(folder.id) },
                        trailing = if (folder.id == currentId) ({ CurrentMark() }) else null,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.document_cancel), color = colors.onSurfaceVariant) }
        },
    )
}

@Composable
private fun CurrentMark() {
    Icon(
        Icons.Rounded.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
