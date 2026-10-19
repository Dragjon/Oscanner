package dev.olaf.oscanner.feature.document

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RotateRight
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.DocumentRepository
import dev.olaf.oscanner.core.data.PageStore
import dev.olaf.oscanner.core.design.Dock
import dev.olaf.oscanner.core.design.DockAction
import dev.olaf.oscanner.core.design.rememberPageImage
import dev.olaf.oscanner.core.model.Page
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ArrangeViewModel(
    private val documents: DocumentRepository,
    private val store: PageStore,
    val documentId: Long,
) : ViewModel() {

    val pages: StateFlow<List<Page>?> = store.observePages(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val extracted = Channel<Long>(Channel.BUFFERED)
    val extractedDocuments = extracted.receiveAsFlow()

    fun move(from: Int, to: Int) {
        viewModelScope.launch { store.movePage(documentId, from, to) }
    }

    fun rotate(ids: List<Long>) {
        viewModelScope.launch { store.rotatePages(ids) }
    }

    fun delete(ids: List<Long>) {
        viewModelScope.launch { store.deletePages(ids) }
    }

    fun extract(ids: List<Long>, title: String) {
        viewModelScope.launch {
            runCatching { store.extractPages(documentId, ids, title) }.onSuccess { extracted.send(it) }
        }
    }

    suspend fun title(): String = documents.observeDocument(documentId).first()?.title.orEmpty()
}

/**
 * Every page as a tile. Tap tiles to choose them, then move, turn, copy into a new document or
 * delete. Moving is by buttons rather than dragging, so it also works with TalkBack.
 */
@Composable
fun ArrangeRoute(viewModel: ArrangeViewModel, onBack: () -> Unit, onOpenDocument: (Long) -> Unit) {
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val chosen = rememberSaveable(saver = idListSaver) { mutableListOf<Long>().toMutableStateList() }
    var pendingDelete by remember { mutableStateOf<List<Long>>(emptyList()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedText = stringResource(R.string.document_pages_deleted)
    val extractedText = stringResource(R.string.document_pages_extracted)
    val undo = stringResource(R.string.document_undo)
    val open = stringResource(R.string.document_open)
    val extractTitle = stringResource(R.string.document_extract_title)

    LaunchedEffect(viewModel) {
        viewModel.extractedDocuments.collect { id ->
            if (snackbar.showSnackbar(extractedText, actionLabel = open, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                onOpenDocument(id)
            }
        }
    }

    val all = pages ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val visible = all.filter { it.id !in pendingDelete }
    // Ignore choices whose pages are gone (deleted elsewhere, or waiting out an Undo).
    val picked = chosen.filter { id -> visible.any { it.id == id } }
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
                    Text(stringResource(R.string.document_arrange), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.document_arrange_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (picked.isNotEmpty()) {
                    TextButton(onClick = { chosen.clear() }) { Text(stringResource(R.string.document_clear_choice)) }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(visible, key = { _, page -> page.id }) { index, page ->
                    PageTile(
                        page = page,
                        number = index + 1,
                        selected = page.id in chosen,
                        onClick = { if (page.id in chosen) chosen.remove(page.id) else chosen.add(page.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            // With one page chosen it can be moved; with several, the actions apply to all of them.
            val single = picked.singleOrNull()?.let { id -> visible.indexOfFirst { it.id == id } }
            if (single != null && single >= 0) {
                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { viewModel.move(single, single - 1) }, enabled = single > 0) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.document_move_earlier))
                    }
                    TextButton(onClick = { viewModel.move(single, single + 1) }, enabled = single < visible.lastIndex) {
                        Text(stringResource(R.string.document_move_later))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }

            val any = picked.isNotEmpty()
            // Deleting every page would leave an empty document; Trash is the way to remove it.
            val canDelete = any && picked.size < visible.size
            Dock(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(),
            ) {
                DockAction(Icons.Rounded.RotateRight, stringResource(R.string.document_rotate), { viewModel.rotate(picked) }, enabled = any)
                DockAction(Icons.Rounded.CallSplit, stringResource(R.string.document_extract), {
                    val ids = visible.filter { it.id in picked }.map { it.id }
                    scope.launch { viewModel.extract(ids, String.format(extractTitle, viewModel.title())) }
                    chosen.clear()
                }, enabled = any)
                DockAction(Icons.Rounded.DeleteOutline, stringResource(R.string.document_delete_pages), {
                    val ids = picked
                    chosen.clear()
                    pendingDelete = pendingDelete + ids
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        val result = snackbar.showSnackbar(deletedText, actionLabel = undo, duration = SnackbarDuration.Short)
                        if (result == SnackbarResult.Dismissed) viewModel.delete(ids)
                        pendingDelete = pendingDelete - ids.toSet()
                    }
                }, enabled = canDelete)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 104.dp))
    }
}

@Composable
private fun PageTile(page: Page, number: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val image by rememberPageImage(page.thumbPath ?: page.renderPath, maxSide = 320)
    val description = stringResource(R.string.document_page_number, number)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else colors.surfaceContainer,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
        modifier = modifier.semantics {
            role = Role.Checkbox
            this.selected = selected
            contentDescription = description
        },
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .clip(MaterialTheme.shapes.small)
                    .background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.padding(4.dp)) }
                if (selected) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(colors.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

private val idListSaver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<Long>, Long>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)
