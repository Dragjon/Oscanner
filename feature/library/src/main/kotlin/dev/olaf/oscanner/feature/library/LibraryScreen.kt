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
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
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
    LibraryScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onQueryChange = viewModel::onQueryChange,
        onFolderSelected = viewModel::onFolderSelected,
        onCreateFolder = viewModel::createFolder,
        onSortOrderChange = viewModel::onSortOrderChange,
        onScan = onScan,
        onImport = onImport,
        onOpenDocument = onOpenDocument,
        onOpenSettings = onOpenSettings,
        onOpenTrash = onOpenTrash,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onFolderSelected: (Long?) -> Unit,
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
    val statusBar = WindowInsets.statusBars.asPaddingValues()
    val navBar = WindowInsets.navigationBars.asPaddingValues()

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = statusBar.calculateTopPadding() + 24.dp,
                bottom = navBar.calculateBottomPadding() + DockClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.library_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
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
                if (state.documents.isEmpty()) {
                    item(key = "no-results") {
                        Text(
                            if (state.query.isNotBlank()) {
                                stringResource(R.string.library_no_results, state.query.trim())
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
                        onClick = { onOpenDocument(document.id) },
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
                DockAction(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.library_import), onImport)
                PrimaryPill(Icons.Rounded.DocumentScanner, stringResource(R.string.library_scan), onScan)
                DockAction(Icons.Rounded.MoreHoriz, stringResource(R.string.library_more), { showMore = true })
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
            )
        }
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
