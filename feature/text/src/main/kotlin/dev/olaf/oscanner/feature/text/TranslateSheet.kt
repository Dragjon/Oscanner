package dev.olaf.oscanner.feature.text

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.translate.TranslationLanguage

/**
 * Translate the document: from which language (guessed from the text) into which, and the
 * download first if either isn't on the phone. Translating carries on with the sheet closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslateSheet(
    translation: TranslationUi,
    onFrom: (String) -> Unit,
    onTo: (String) -> Unit,
    onTranslate: () -> Unit,
    onCancel: () -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val byCode = translation.languages.associateBy { it.code }
    fun name(code: String) = byCode[code]?.name ?: code
    val missing = listOf(translation.from, translation.to).distinct().mapNotNull { byCode[it] }.filterNot { it.installed }
    val work = translation.work

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.text_translate), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LanguageField(stringResource(R.string.text_translate_from), name(translation.from)) { picking = FROM }
                    LanguageField(stringResource(R.string.text_translate_to), name(translation.to)) { picking = TO }
                }
                IconButton(onClick = {
                    val from = translation.from
                    onFrom(translation.to)
                    onTo(from)
                }) {
                    Icon(Icons.Rounded.SwapVert, contentDescription = stringResource(R.string.text_translate_swap))
                }
            }
            when (work) {
                null, TranslationWork.Failed -> {
                    if (work == TranslationWork.Failed) {
                        Text(stringResource(R.string.text_translate_failed), style = MaterialTheme.typography.bodyMedium, color = colors.error)
                    }
                    if (missing.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.text_translate_download,
                                missing.joinToString { it.name },
                                Formatter.formatShortFileSize(context, missing.sumOf { it.sizeBytes }),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    if (translation.from == translation.to) {
                        Text(stringResource(R.string.text_translate_same), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    } else {
                        PrimaryPill(
                            Icons.Rounded.Translate,
                            stringResource(if (missing.isEmpty()) R.string.text_translate else R.string.text_translate_download_and),
                            onClick = onTranslate,
                        )
                    }
                    Text(stringResource(R.string.text_translate_private), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                else -> {
                    TranslationProgress(work)
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.text_translate_stop), color = colors.onSurfaceVariant) }
                }
            }
        }
    }

    picking?.let { side ->
        LanguagePicker(
            languages = translation.languages,
            selected = if (side == FROM) translation.from else translation.to,
            onPick = { code ->
                picking = null
                if (side == FROM) onFrom(code) else onTo(code)
            },
            // Not while it may be in use.
            onRemove = if (work == null || work == TranslationWork.Failed) onRemove else null,
            onDismiss = { picking = null },
        )
    }
}

@Composable
internal fun TranslationProgress(work: TranslationWork) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            when (work) {
                is TranslationWork.Downloading -> stringResource(R.string.text_translate_downloading, (work.fraction * 100).toInt())
                is TranslationWork.Translating -> stringResource(R.string.text_translate_page, work.page, work.pages)
                else -> stringResource(R.string.text_translate_preparing)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
        when (work) {
            is TranslationWork.Downloading -> LinearProgressIndicator(
                progress = { work.fraction },
                modifier = Modifier.fillMaxWidth(),
                color = colors.primary,
                trackColor = colors.surfaceContainerHighest,
            )
            is TranslationWork.Translating -> LinearProgressIndicator(
                progress = { (work.page - 1f) / work.pages },
                modifier = Modifier.fillMaxWidth(),
                color = colors.primary,
                trackColor = colors.surfaceContainerHighest,
            )
            else -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary, trackColor = colors.surfaceContainerHighest)
        }
    }
}

@Composable
private fun LanguageField(label: String, value: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.width(56.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** Every language, those on the phone marked; the others say how much they'd download. */
@Composable
private fun LanguagePicker(
    languages: List<TranslationLanguage>,
    selected: String,
    onPick: (String) -> Unit,
    onRemove: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val ordered = languages.sortedBy { !it.installed }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(stringResource(R.string.text_translate_language)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(ordered, key = { it.code }) { language ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(language.code) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            language.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (language.code == selected) colors.primary else colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (language.installed) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.text_translate_on_phone), tint = colors.primary, modifier = Modifier.size(18.dp))
                            // English is built into every pair; the others can be removed for space.
                            if (onRemove != null && language.sizeBytes > 0) {
                                IconButton(onClick = { onRemove(language.code) }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = stringResource(R.string.text_translate_remove, language.name),
                                        tint = colors.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        } else {
                            Text(Formatter.formatShortFileSize(context, language.sizeBytes), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.text_translate_close), color = colors.onSurfaceVariant) }
        },
    )
}

private const val FROM = "from"
private const val TO = "to"
