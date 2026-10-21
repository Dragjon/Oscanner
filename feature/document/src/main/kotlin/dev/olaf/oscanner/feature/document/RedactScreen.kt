package dev.olaf.oscanner.feature.document

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import dev.olaf.oscanner.core.design.detectDragsFromTouch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.design.Dock
import dev.olaf.oscanner.core.design.DockAction
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.design.SheetAction
import dev.olaf.oscanner.core.model.Corner
import kotlin.math.min

/**
 * Black out parts of a page: drag a box, or let Find cover email addresses, long numbers or a
 * name everywhere it appears. Tap a box to take it away. Nothing changes until Apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactRoute(viewModel: RedactViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var finding by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    val leave = { if (state.changed) confirmDiscard = true else onDone() }
    BackHandler { leave() }

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
            IconButton(onClick = leave) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.document_back))
            }
            Text(stringResource(R.string.document_redact), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = viewModel::undo, enabled = state.areas.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.document_undo))
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (state.base == null) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = colors.onSurfaceVariant)
            } else {
                RedactCanvas(state, onBox = viewModel::addBox, onTap = { viewModel.removeAt(it) }, modifier = Modifier.fillMaxSize())
            }
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                onClick = { finding = true },
                enabled = state.base != null,
                shape = MaterialTheme.shapes.medium,
                color = colors.surfaceContainer,
                contentColor = colors.onSurface,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.document_redact_find), style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                stringResource(R.string.document_redact_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }

        Dock(Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(16.dp).widthIn(max = 480.dp).fillMaxWidth()) {
            DockAction(Icons.Rounded.Close, stringResource(R.string.document_cancel), leave)
            PrimaryPill(Icons.Rounded.Check, stringResource(R.string.document_apply), viewModel::save)
        }
    }

    if (finding) {
        FindSheet(
            state = state,
            onCover = { groups ->
                finding = false
                viewModel.cover(groups)
            },
            onDismiss = { finding = false },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = colors.surfaceContainer,
            title = { Text(stringResource(R.string.document_redact_discard)) },
            confirmButton = { TextButton(onClick = viewModel::save) { Text(stringResource(R.string.document_apply)) } },
            dismissButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone()
                }) { Text(stringResource(R.string.document_discard), color = colors.onSurfaceVariant) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindSheet(state: RedactUiState, onCover: (List<List<dev.olaf.oscanner.core.model.WordBox>>) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val words = state.uncovered
    val emails = remember(words) { PrivateDetails.emails(words) }
    val numbers = remember(words) { PrivateDetails.numbers(words) }
    val lines = remember(words) { PrivateDetails.lines(words) }
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(words, query) { PrivateDetails.matching(words, query) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.words.isEmpty()) {
                Text(
                    stringResource(R.string.document_redact_not_read),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
                return@Column
            }
            if (emails.isNotEmpty()) {
                SheetAction(
                    Icons.Rounded.AlternateEmail,
                    stringResource(R.string.document_redact_emails),
                    pluralStringResource(R.plurals.document_redact_found, emails.size, emails.size),
                    { onCover(emails) },
                )
            }
            if (numbers.isNotEmpty()) {
                SheetAction(
                    Icons.Rounded.Numbers,
                    stringResource(R.string.document_redact_numbers),
                    pluralStringResource(R.plurals.document_redact_numbers_found, numbers.size, numbers.size),
                    { onCover(numbers) },
                )
            }
            if (lines.isNotEmpty()) {
                SheetAction(
                    Icons.AutoMirrored.Rounded.Notes,
                    stringResource(R.string.document_redact_all),
                    pluralStringResource(R.plurals.document_redact_lines_found, lines.size, lines.size),
                    { onCover(lines) },
                )
            }
            if (emails.isEmpty() && numbers.isEmpty()) {
                Text(
                    stringResource(R.string.document_redact_none_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
            Text(
                stringResource(R.string.document_redact_word),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.replace("\n", "") },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.document_redact_word_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (matches.isNotEmpty()) onCover(matches) }),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.onSurfaceVariant,
                        unfocusedBorderColor = colors.outlineVariant,
                        cursorColor = colors.primary,
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onCover(matches) }, enabled = matches.isNotEmpty(), modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.document_redact_cover, matches.size))
                }
            }
            if (query.isNotBlank() && matches.isEmpty()) {
                Text(
                    stringResource(R.string.document_redact_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/** The page with its black boxes; drag to add one, tap one to take it away. */
@Composable
private fun RedactCanvas(state: RedactUiState, onBox: (Corner, Corner) -> Unit, onTap: (Corner) -> Unit, modifier: Modifier = Modifier) {
    val base = remember(state.base) { state.base!!.asImageBitmap() }
    val outline = MaterialTheme.colorScheme.primary
    var start by remember { mutableStateOf<Corner?>(null) }
    var end by remember { mutableStateOf<Corner?>(null) }

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val scale = min(w / base.width, h / base.height)
        val shown = Rect(Offset((w - base.width * scale) / 2, (h - base.height * scale) / 2), Size(base.width * scale, base.height * scale))
        fun toPage(o: Offset) = Corner(((o.x - shown.left) / shown.width).coerceIn(0f, 1f), ((o.y - shown.top) / shown.height).coerceIn(0f, 1f))
        fun toScreen(c: Corner) = Offset(shown.left + c.x * shown.width, shown.top + c.y * shown.height)

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(shown) {
                    detectDragsFromTouch(
                        onDragStart = {
                            start = toPage(it)
                            end = start
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            end = toPage(change.position)
                        },
                        onDragEnd = {
                            val a = start
                            val b = end
                            if (a != null && b != null) onBox(a, b)
                            start = null
                            end = null
                        },
                        onDragCancel = {
                            start = null
                            end = null
                        },
                    )
                }
                .pointerInput(shown) { detectTapGestures { onTap(toPage(it)) } },
        ) {
            drawImage(base, dstOffset = IntOffset(shown.left.toInt(), shown.top.toInt()), dstSize = IntSize(shown.width.toInt(), shown.height.toInt()))
            for (shape in state.shapes) {
                val path = Path().apply {
                    shape.forEachIndexed { i, c -> toScreen(c).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                    close()
                }
                drawPath(path, Color.Black)
            }
            val a = start
            val b = end
            if (a != null && b != null) {
                val topLeft = toScreen(Corner(min(a.x, b.x), min(a.y, b.y)))
                val bottomRight = toScreen(Corner(maxOf(a.x, b.x), maxOf(a.y, b.y)))
                val size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y)
                drawRect(Color.Black.copy(alpha = 0.6f), topLeft, size)
                drawRect(outline, topLeft, size, style = Stroke(2.dp.toPx()))
            }
        }
    }
}
