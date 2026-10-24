package dev.olaf.oscanner.feature.library

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.design.ChalkSnowman
import dev.olaf.oscanner.core.design.Dock
import dev.olaf.oscanner.core.design.DockAction
import dev.olaf.oscanner.core.design.DocumentCard
import dev.olaf.oscanner.core.design.OlafSays
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.design.R as DesignR
import dev.olaf.oscanner.core.design.SearchField
import dev.olaf.oscanner.core.design.SheetAction
import dev.olaf.oscanner.core.design.share
import dev.olaf.oscanner.core.model.Folder
import dev.olaf.oscanner.core.model.SortOrder
import dev.olaf.oscanner.core.model.Tag

/** Where Oscanner's source code lives. */
private const val SOURCE_URL = "https://github.com/Dragjon/Oscanner"

/** Space the list leaves under its last card so the floating dock never covers it. */
private val DockClearance = 112.dp

@Composable
fun LibraryRoute(
    viewModel: LibraryViewModel,
    snackbarHostState: SnackbarHostState,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onImportFiles: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    versionName: String,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val preparing by viewModel.preparing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val noApp = stringResource(R.string.library_no_share_app)
    LaunchedEffect(viewModel) {
        viewModel.shareFlow.collect { files ->
            if (!context.share(files, "application/pdf")) snackbarHostState.showSnackbar(noApp)
        }
    }
    val trashed = stringResource(R.string.library_moved_to_trash)
    val merged = stringResource(R.string.library_merged)
    val undo = stringResource(R.string.library_undo)
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            val text = when (event) {
                is LibraryEvent.Trashed -> trashed
                is LibraryEvent.Merged -> merged
            }
            val result = snackbarHostState.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                when (event) {
                    is LibraryEvent.Trashed -> viewModel.restore(event.ids)
                    is LibraryEvent.Merged -> viewModel.unmerge(event)
                }
            }
        }
    }
    BackHandler(enabled = selection != null) { viewModel.stopSelecting() }
    LibraryScreen(
        state = state,
        selection = Selection(
            chosen = selection,
            start = viewModel::startSelecting,
            toggle = viewModel::toggleSelected,
            stop = viewModel::stopSelecting,
            move = viewModel::moveSelected,
            merge = viewModel::mergeSelected,
            share = viewModel::shareSelected,
            trash = viewModel::trashSelected,
        ),
        preparing = preparing,
        snackbarHostState = snackbarHostState,
        onQueryChange = viewModel::onQueryChange,
        onFolderSelected = viewModel::onFolderSelected,
        onTagSelected = viewModel::onTagSelected,
        onCreateFolder = viewModel::createFolder,
        onRenameFolder = viewModel::renameFolder,
        onDeleteFolder = viewModel::deleteFolder,
        folderDocumentCount = viewModel::documentCount,
        onSortOrderChange = viewModel::onSortOrderChange,
        onScan = onScan,
        onImport = onImport,
        onImportFiles = onImportFiles,
        onOpenDocument = onOpenDocument,
        onOpenSettings = onOpenSettings,
        onOpenTrash = onOpenTrash,
        versionName = versionName,
    )
}

/** Choosing several documents at once; [chosen] is null when not choosing. */
class Selection(
    val chosen: List<Long>?,
    val start: (Long?) -> Unit,
    val toggle: (Long) -> Unit,
    val stop: () -> Unit,
    val move: (Long?) -> Unit,
    val merge: () -> Unit,
    val share: () -> Unit,
    val trash: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    selection: Selection,
    /** True while chosen documents are being made into PDFs to share. */
    preparing: Boolean,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onFolderSelected: (Long?) -> Unit,
    onTagSelected: (Long) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (Long, String) -> Unit,
    onDeleteFolder: (id: Long, trashDocuments: Boolean) -> Unit,
    folderDocumentCount: suspend (Long) -> Int,
    onSortOrderChange: (SortOrder) -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onImportFiles: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    versionName: String,
) {
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var showNewFolder by rememberSaveable { mutableStateOf(false) }
    var showMoveSelected by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    val chosen = selection.chosen
    val statusBar = WindowInsets.statusBars.asPaddingValues()
    val navBar = WindowInsets.navigationBars.asPaddingValues()
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    // Scrolling the list or tapping anywhere off the search field ends the search edit.
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) focusManager.clearFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        LazyColumn(
            state = listState,
            // On tablets the list stays a readable width, centered.
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = statusBar.calculateTopPadding() + 24.dp,
                bottom = navBar.calculateBottomPadding() + DockClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                if (chosen == null) {
                    BrandHeader(versionName, Modifier.padding(start = 4.dp, bottom = 8.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        IconButton(onClick = selection.stop) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.library_stop_selecting))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            pluralStringResource(R.plurals.library_selected, chosen.size, chosen.size),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
            if (state.hasDocuments) {
                item(key = "search") {
                    SearchField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        placeholder = stringResource(R.string.library_search_hint),
                    )
                }
                item(key = "folders") {
                    FolderChips(
                        folders = state.folders,
                        selectedId = state.selectedFolderId,
                        onSelect = onFolderSelected,
                        onNewFolder = { showNewFolder = true },
                        onRename = { renaming = it.id },
                        onDelete = { deleting = it.id },
                    )
                }
                if (state.tags.isNotEmpty()) {
                    item(key = "tags") {
                        TagChips(tags = state.tags, selectedId = state.selectedTagId, onSelect = onTagSelected)
                    }
                }
                if (state.documents.isEmpty()) {
                    item(key = "no-results") {
                        Text(
                            if (state.query.isNotBlank()) {
                                stringResource(R.string.library_no_results, state.query.trim())
                            } else if (state.selectedTagId != null) {
                                stringResource(R.string.library_tag_empty)
                            } else {
                                stringResource(R.string.library_folder_empty)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 24.dp),
                        )
                    }
                }
                // Under All, what's in a folder is grouped under the folder's name, after what isn't.
                val sections = if (state.selectedFolderId == null) {
                    librarySections(state.documents, state.folders)
                } else {
                    listOf(LibrarySection(null, state.documents))
                }
                sections.forEach { section ->
                    section.folder?.let { folder ->
                        item(key = "section-${folder.id}") {
                            SectionHeader(
                                folder = folder,
                                count = section.documents.size,
                                onOpen = { onFolderSelected(folder.id) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    items(section.documents, key = { it.id }) { document ->
                        DocumentCard(
                            document = document,
                            onClick = { if (chosen != null) selection.toggle(document.id) else onOpenDocument(document.id) },
                            onLongClick = { if (chosen == null) selection.start(document.id) else selection.toggle(document.id) },
                            selected = chosen?.let { document.id in it },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                if (state.documents.isNotEmpty()) {
                    // At the end of the list, the snowman with something to say.
                    item(key = "olaf") {
                        Box(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                            OlafSays()
                        }
                    }
                }
            }
        }

        if (!state.loading && !state.hasDocuments) {
            // Nothing scanned yet: a chalk snowman with a page, and what to do first.
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .navigationBarsPadding()
                    .padding(start = 32.dp, end = 32.dp, top = 120.dp, bottom = DockClearance)
                    .widthIn(max = 320.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ChalkSnowman(Modifier.size(width = 200.dp, height = 220.dp))
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.library_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbarHostState)
            if (preparing) PreparingLine()
            Dock(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                if (chosen == null) {
                    DockAction(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.library_import), { showImport = true })
                    PrimaryPill(Icons.Rounded.DocumentScanner, stringResource(R.string.library_scan), onScan)
                    DockAction(Icons.Rounded.MoreHoriz, stringResource(R.string.library_more), { showMore = true })
                } else {
                    val any = chosen.isNotEmpty()
                    DockAction(Icons.Rounded.DriveFileMove, stringResource(R.string.library_move), { showMoveSelected = true }, enabled = any)
                    DockAction(Icons.Rounded.CallMerge, stringResource(R.string.library_merge), selection.merge, enabled = chosen.size >= 2)
                    DockAction(Icons.Rounded.Share, stringResource(R.string.library_share), selection.share, enabled = any)
                    DockAction(Icons.Rounded.DeleteOutline, stringResource(R.string.library_delete), selection.trash, enabled = any)
                }
            }
        }
    }

    if (showImport) {
        ModalBottomSheet(
            onDismissRequest = { showImport = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SheetAction(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = stringResource(R.string.library_import_photos),
                    description = stringResource(R.string.library_import_photos_description),
                    onClick = {
                        showImport = false
                        onImport()
                    },
                )
                SheetAction(
                    icon = Icons.Rounded.FileOpen,
                    title = stringResource(R.string.library_import_files),
                    description = stringResource(R.string.library_import_files_description),
                    onClick = {
                        showImport = false
                        onImportFiles()
                    },
                )
            }
        }
    }

    if (showMore) {
        ModalBottomSheet(
            onDismissRequest = { showMore = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            MoreSheet(
                sortOrder = state.sortOrder,
                onSortOrderChange = onSortOrderChange,
                onNewFolder = {
                    showMore = false
                    showNewFolder = true
                },
                onOpenSettings = {
                    showMore = false
                    onOpenSettings()
                },
                onOpenTrash = {
                    showMore = false
                    onOpenTrash()
                },
                onSelect = {
                    showMore = false
                    selection.start(null)
                },
                folder = state.folders.firstOrNull { it.id == state.selectedFolderId },
                onRenameFolder = {
                    showMore = false
                    renaming = state.selectedFolderId
                },
                onDeleteFolder = {
                    showMore = false
                    deleting = state.selectedFolderId
                },
            )
        }
    }

    if (showMoveSelected) {
        FolderPicker(
            folders = state.folders,
            onPick = {
                showMoveSelected = false
                selection.move(it)
            },
            onDismiss = { showMoveSelected = false },
        )
    }

    if (showNewFolder) {
        NewFolderDialog(
            onCreate = { name ->
                showNewFolder = false
                onCreateFolder(name)
            },
            onDismiss = { showNewFolder = false },
        )
    }

    state.folders.firstOrNull { it.id == renaming }?.let { folder ->
        NewFolderDialog(
            onCreate = { name ->
                renaming = null
                onRenameFolder(folder.id, name)
            },
            onDismiss = { renaming = null },
            initial = folder.name,
            title = stringResource(R.string.library_rename_folder),
            confirm = stringResource(R.string.library_rename),
        )
    }

    state.folders.firstOrNull { it.id == deleting }?.let { folder ->
        DeleteFolderDialog(
            folder = folder,
            documentCount = folderDocumentCount,
            onDelete = { trash ->
                deleting = null
                onDeleteFolder(folder.id, trash)
            },
            onDismiss = { deleting = null },
        )
    }
}

/** The app's mark, name and version: the home screen's title. */
@Composable
private fun BrandHeader(versionName: String, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(DesignR.drawable.oscanner_logo), contentDescription = null, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.library_app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.alignByBaseline(),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            versionName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

@Composable
private fun PreparingLine() {
    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.library_preparing_pdf),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FolderChips(
    folders: List<Folder>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onNewFolder: () -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
) {
    // The folder whose menu is open, from a long press on its chip.
    var menuFor by remember { mutableStateOf<Long?>(null) }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        item(key = "all") {
            FolderChip(stringResource(R.string.library_all), selected = selectedId == null) { onSelect(null) }
        }
        items(folders, key = { it.id }) { folder ->
            Box {
                FolderChip(folder.name, selected = folder.id == selectedId, onLongClick = { menuFor = folder.id }) { onSelect(folder.id) }
                DropdownMenu(
                    expanded = menuFor == folder.id,
                    onDismissRequest = { menuFor = null },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_rename_folder)) },
                        leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
                        onClick = {
                            menuFor = null
                            onRename(folder)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_delete_folder)) },
                        leadingIcon = { Icon(Icons.Rounded.FolderDelete, contentDescription = null) },
                        onClick = {
                            menuFor = null
                            onDelete(folder)
                        },
                    )
                }
            }
        }
        item(key = "new") {
            FolderChip(stringResource(R.string.library_new_folder), selected = false, leadingAdd = true, onClick = onNewFolder)
        }
    }
}

/** A folder's name over its documents under All; tapping it opens the folder. */
@Composable
private fun SectionHeader(folder: Folder, count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.library_open_folder), onClick = onOpen)
            .semantics(mergeDescendants = true) { heading() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Folder, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            folder.name,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text("$count", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** Tags filter on top of the folder; they sit on a second row so folders keep their place. */
@Composable
private fun TagChips(tags: List<Tag>, selectedId: Long?, onSelect: (Long) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tags, key = { it.id }) { tag ->
            FolderChip("#${tag.name}", selected = tag.id == selectedId) { onSelect(tag.id) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    leadingAdd: Boolean = false,
    /** A folder's chip opens its menu on a long press; others have none. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val content = if (selected) colors.onPrimary else colors.onSurfaceVariant
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.primary else colors.surfaceContainer)
            .combinedClickable(
                role = Role.Tab,
                onClick = onClick,
                onLongClick = onLongClick?.let { open ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        open()
                    }
                },
                onLongClickLabel = onLongClick?.let { stringResource(R.string.library_folder_options) },
            )
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingAdd) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = content, modifier = Modifier.padding(end = 8.dp).size(18.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
    onNewFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    onSelect: () -> Unit,
    /** The folder being looked at, which can be renamed or deleted from here. */
    folder: Folder?,
    onRenameFolder: () -> Unit,
    onDeleteFolder: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetAction(
            icon = Icons.Rounded.Checklist,
            title = stringResource(R.string.library_select),
            description = stringResource(R.string.library_select_description),
            onClick = onSelect,
        )
        if (folder != null) {
            SheetAction(
                icon = Icons.Rounded.DriveFileRenameOutline,
                title = stringResource(R.string.library_rename_folder),
                description = folder.name,
                onClick = onRenameFolder,
            )
            SheetAction(
                icon = Icons.Rounded.FolderDelete,
                title = stringResource(R.string.library_delete_folder),
                description = stringResource(R.string.library_delete_folder_description),
                onClick = onDeleteFolder,
            )
        }
        SheetAction(
            icon = Icons.Rounded.CreateNewFolder,
            title = stringResource(R.string.library_new_folder),
            description = stringResource(R.string.library_new_folder_description),
            onClick = onNewFolder,
        )
        SheetAction(
            icon = Icons.Rounded.DeleteOutline,
            title = stringResource(R.string.library_trash),
            description = stringResource(R.string.library_trash_description),
            onClick = onOpenTrash,
        )
        SheetAction(
            icon = Icons.Rounded.Settings,
            title = stringResource(R.string.library_settings),
            description = stringResource(R.string.library_settings_description),
            onClick = onOpenSettings,
        )
        val context = LocalContext.current
        SheetAction(
            icon = Icons.Rounded.Code,
            title = stringResource(R.string.library_source),
            description = stringResource(R.string.library_source_description),
            onClick = {
                // Opens in the browser; the app itself never goes online for this.
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.library_sort_by),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        val options = listOf(
            SortOrder.Newest to R.string.library_sort_newest,
            SortOrder.Oldest to R.string.library_sort_oldest,
            SortOrder.Name to R.string.library_sort_name,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (order, label) ->
                SegmentedButton(
                    selected = order == sortOrder,
                    onClick = { onSortOrderChange(order) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    icon = {
                        if (order == sortOrder) {
                            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        activeBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** A folder's name: for a new folder, or, given [initial] and [title], to rename one. */
@Composable
private fun NewFolderDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    title: String = stringResource(R.string.library_new_folder),
    confirm: String = stringResource(R.string.library_create),
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.library_folder_name_hint)) },
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.onSurfaceVariant,
                    unfocusedBorderColor = colors.outlineVariant,
                    cursorColor = colors.primary,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text(confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_cancel), color = colors.onSurfaceVariant)
            }
        },
    )
}

/**
 * Confirms deleting [folder]. Its documents are kept, in no folder, unless the person ticks to
 * put them in Trash too, from where they can still be restored.
 */
@Composable
private fun DeleteFolderDialog(
    folder: Folder,
    documentCount: suspend (Long) -> Int,
    onDelete: (trashDocuments: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var count by remember { mutableStateOf<Int?>(null) }
    var trash by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(folder.id) { count = documentCount(folder.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(stringResource(R.string.library_delete_folder_confirm, folder.name)) },
        text = {
            val n = count
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    n == null -> Unit
                    n == 0 -> Text(stringResource(R.string.library_delete_folder_empty), color = colors.onSurfaceVariant)
                    else -> {
                        Text(
                            if (trash) {
                                pluralStringResource(R.plurals.library_delete_folder_trash, n, n)
                            } else {
                                pluralStringResource(R.plurals.library_delete_folder_keep, n, n)
                            },
                            color = colors.onSurfaceVariant,
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .toggleable(value = trash, role = Role.Checkbox, onValueChange = { trash = it }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = trash, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                            Text(stringResource(R.string.library_delete_folder_with_documents), color = colors.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDelete(trash && (count ?: 0) > 0) }, enabled = count != null) {
                Text(stringResource(R.string.library_delete), color = colors.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_cancel), color = colors.onSurfaceVariant)
            }
        },
    )
}

/** Where to move the chosen documents: no folder, or one of the folders. */
@Composable
private fun FolderPicker(folders: List<Folder>, onPick: (Long?) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(stringResource(R.string.library_move_to)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SheetAction(Icons.Rounded.FolderOff, stringResource(R.string.library_no_folder), null, { onPick(null) })
                folders.forEach { folder -> SheetAction(Icons.Rounded.Folder, folder.name, null, { onPick(folder.id) }) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel), color = colors.onSurfaceVariant) }
        },
    )
}
