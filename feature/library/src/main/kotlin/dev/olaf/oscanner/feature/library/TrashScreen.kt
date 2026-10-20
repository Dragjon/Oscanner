package dev.olaf.oscanner.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.DocumentRepository
import dev.olaf.oscanner.core.data.PageStore
import dev.olaf.oscanner.core.design.DocumentCard
import dev.olaf.oscanner.core.design.SheetAction
import dev.olaf.oscanner.core.model.Document
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrashViewModel(
    private val documents: DocumentRepository,
    private val pages: PageStore,
) : ViewModel() {

    val trash: StateFlow<List<Document>?> = documents.observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun restore(id: Long) {
        viewModelScope.launch { documents.restore(id) }
    }

    fun deleteForever(id: Long) {
        viewModelScope.launch { pages.deleteForever(id) }
    }

    fun emptyTrash() {
        viewModelScope.launch { pages.emptyTrash() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashRoute(viewModel: TrashViewModel, onBack: () -> Unit) {
    val trash by viewModel.trash.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirm by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val statusBar = WindowInsets.statusBars.asPaddingValues()
    val navBar = WindowInsets.navigationBars.asPaddingValues()
    val items = trash.orEmpty()

    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = statusBar.calculateTopPadding() + 8.dp,
            bottom = navBar.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.library_back))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.library_trash),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onBackground,
                    modifier = Modifier.weight(1f),
                )
                if (items.isNotEmpty()) {
                    TextButton(onClick = { confirmEmpty = true }) { Text(stringResource(R.string.library_empty_trash)) }
                }
            }
        }
        item(key = "explain") {
            Text(
                stringResource(if (trash != null && items.isEmpty()) R.string.library_trash_empty else R.string.library_trash_explain),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        items(items, key = { it.id }) { document ->
            DocumentCard(document = document, onClick = { selectedId = document.id }, modifier = Modifier.animateItem())
        }
    }

    }

    selectedId?.let { id ->
        ModalBottomSheet(
            onDismissRequest = { selectedId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surfaceContainer,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SheetAction(
                    icon = Icons.Rounded.RestoreFromTrash,
                    title = stringResource(R.string.library_restore),
                    description = stringResource(R.string.library_restore_description),
                    onClick = {
                        selectedId = null
                        viewModel.restore(id)
                    },
                )
                SheetAction(
                    icon = Icons.Rounded.DeleteForever,
                    title = stringResource(R.string.library_delete_forever),
                    description = stringResource(R.string.library_delete_forever_description),
                    onClick = {
                        selectedId = null
                        confirm = id
                    },
                )
            }
        }
    }

    // Permanent delete is the one place we confirm instead of offering Undo (PLAN.md §6).
    confirm?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.library_delete_forever_confirm),
            confirmLabel = stringResource(R.string.library_delete_forever),
            onConfirm = {
                confirm = null
                viewModel.deleteForever(id)
            },
            onDismiss = { confirm = null },
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = stringResource(R.string.library_empty_trash_confirm),
            confirmLabel = stringResource(R.string.library_empty_trash),
            onConfirm = {
                confirmEmpty = false
                viewModel.emptyTrash()
            },
            onDismiss = { confirmEmpty = false },
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(title) },
        text = { Text(stringResource(R.string.library_cant_undo), color = colors.onSurfaceVariant) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = colors.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel), color = colors.onSurfaceVariant) } },
    )
}
