package dev.olaf.oscanner.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.design.Dock
import dev.olaf.oscanner.core.design.DockAction
import dev.olaf.oscanner.core.design.DocumentCard
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.design.SearchField
import dev.olaf.oscanner.core.design.SheetAction
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
    onOpenDocument: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
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
            trash = viewModel::trashSelected,
        ),
        snackbarHostState = snackbarHostState,
        onQueryChange = viewModel::onQueryChange,
        onFolderSelected = viewModel::onFolderSelected,
        onTagSelected = viewModel::onTagSelected,
        onCreateFolder = viewModel::createFolder,
        onSortOrderChange = viewModel::onSortOrderChange,
        onScan = onScan,
        onImport = onImport,
        onOpenDocument = onOpenDocument,
        onOpenSettings = onOpenSettings,
        onOpenTrash = onOpenTrash,
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
    val trash: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    selection: Selection,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onFolderSelected: (Long?) -> Unit,
    onTagSelected: (Long) -> Unit,
    onCreateFolder: (String) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showNewFolder by rememberSaveable { mutableStateOf(false) }
    var showMoveSelected by rememberSaveable { mutableStateOf(false) }
    val chosen = selection.chosen
    val statusBar = WindowInsets.statusBars.asPaddingValues()
    val navBar = WindowInsets.navigationBars.asPaddingValues()

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
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
                    Text(
                        stringResource(R.string.library_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
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
                items(state.documents, key = { it.id }) { document ->
                    DocumentCard(
                        document = document,
                        onClick = { if (chosen != null) selection.toggle(document.id) else onOpenDocument(document.id) },
                        onLongClick = { if (chosen == null) selection.start(document.id) else selection.toggle(document.id) },
                        selected = chosen?.let { document.id in it },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        if (!state.loading && !state.hasDocuments) {
            Text(
                stringResource(R.string.library_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 32.dp, end = 32.dp, bottom = DockClearance + 16.dp)
                    .widthIn(max = 320.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbarHostState)
            Dock(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                if (chosen == null) {
                    DockAction(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.library_import), onImport)
                    PrimaryPill(Icons.Rounded.DocumentScanner, stringResource(R.string.library_scan), onScan)
                    DockAction(Icons.Rounded.MoreHoriz, stringResource(R.string.library_more), { showMore = true })
                } else {
                    val any = chosen.isNotEmpty()
                    DockAction(Icons.Rounded.DriveFileMove, stringResource(R.string.library_move), { showMoveSelected = true }, enabled = any)
                    if (chosen.size >= 2) {
                        PrimaryPill(Icons.Rounded.CallMerge, stringResource(R.string.library_merge), selection.merge)
                    } else {
                        DockAction(Icons.Rounded.CallMerge, stringResource(R.string.library_merge), {}, enabled = false)
                    }
                    DockAction(Icons.Rounded.DeleteOutline, stringResource(R.string.library_delete), selection.trash, enabled = any)
                }
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
}

@Composable
private fun FolderChips(
    folders: List<Folder>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onNewFolder: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        item(key = "all") {
            FolderChip(stringResource(R.string.library_all), selected = selectedId == null) { onSelect(null) }
        }
        items(folders, key = { it.id }) { folder ->
            FolderChip(folder.name, selected = folder.id == selectedId) { onSelect(folder.id) }
        }
        item(key = "new") {
            FolderChip(stringResource(R.string.library_new_folder), selected = false, leadingAdd = true, onClick = onNewFolder)
        }
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

@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    leadingAdd: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = if (leadingAdd) {
            { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        shape = MaterialTheme.shapes.medium,
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surfaceContainer,
            labelColor = colors.onSurfaceVariant,
            iconColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.primary,
            selectedLabelColor = colors.onPrimary,
        ),
        modifier = Modifier.height(40.dp),
    )
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

@Composable
private fun NewFolderDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(stringResource(R.string.library_new_folder)) },
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
                Text(stringResource(R.string.library_create))
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
