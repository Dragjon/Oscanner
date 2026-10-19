package dev.olaf.oscanner.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.SettingsRepository
import dev.olaf.oscanner.core.ocr.Languages
import dev.olaf.oscanner.core.ocr.OcrLanguage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LanguageRow(val language: OcrLanguage, val selected: Boolean, val progress: Float?)

class LanguagesViewModel(
    private val languages: Languages,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val catalog = MutableStateFlow(languages.list())
    /** Code → download progress 0…1 while downloading. */
    private val downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val failures = Channel<Unit>(Channel.CONFLATED)
    val downloadFailed = failures.receiveAsFlow()

    val rows: StateFlow<List<LanguageRow>> = combine(catalog, settings.settings, downloads) { list, prefs, progress ->
        list.map { LanguageRow(it, it.code in prefs.textLanguages, progress[it.code]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggle(code: String) {
        viewModelScope.launch {
            val current = settings.settings.first().textLanguages
            val next = if (code in current) current - code else current + code
            // Something must always be selected; English is the fallback.
            settings.setTextLanguages(next.ifEmpty { listOf(Languages.ENGLISH) })
        }
    }

    fun download(code: String) {
        if (code in downloads.value) return
        downloads.update { it + (code to 0f) }
        viewModelScope.launch {
            val result = runCatching { languages.download(code) { p -> downloads.update { it + (code to p) } } }
            downloads.update { it - code }
            catalog.value = languages.list()
            if (result.isSuccess) {
                val current = settings.settings.first().textLanguages
                if (code !in current) settings.setTextLanguages(current + code)
            } else {
                failures.trySend(Unit)
            }
        }
    }

    fun remove(code: String) {
        viewModelScope.launch {
            settings.setTextLanguages(settings.settings.first().textLanguages - code)
            languages.remove(code)
            catalog.value = languages.list()
        }
    }
}

@Composable
fun LanguagesRoute(viewModel: LanguagesViewModel, onBack: () -> Unit) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failed = stringResource(R.string.settings_language_failed)
    LaunchedEffect(viewModel) { viewModel.downloadFailed.collect { snackbar.showSnackbar(failed) } }
    val colors = MaterialTheme.colorScheme
    val statusBar = WindowInsets.statusBars.asPaddingValues()
    val navBar = WindowInsets.navigationBars.asPaddingValues()

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = statusBar.calculateTopPadding() + 8.dp,
                bottom = navBar.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.settings_back), tint = colors.onBackground)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_languages_title), style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
                }
            }
            item(key = "explain") {
                Text(
                    stringResource(R.string.settings_languages_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
            items(rows, key = { it.language.code }) { row ->
                LanguageCard(row, viewModel::toggle, viewModel::download, viewModel::remove)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }
}

@Composable
private fun LanguageCard(
    row: LanguageRow,
    onToggle: (String) -> Unit,
    onDownload: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val language = row.language
    Surface(
        // Only an installed language can be ticked; the whole card is the checkbox.
        checked = row.selected,
        onCheckedChange = { onToggle(language.code) },
        enabled = language.installed,
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.defaultMinSize(minHeight = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = row.selected && language.installed,
                    onCheckedChange = null,
                    enabled = language.installed,
                    colors = CheckboxDefaults.colors(checkedColor = colors.primary, checkmarkColor = colors.onPrimary),
                )
                Spacer(Modifier.width(12.dp))
                Text(language.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
                when {
                    language.bundled -> Text(stringResource(R.string.settings_language_built_in), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    row.progress != null -> Text(
                        stringResource(R.string.settings_language_downloading, (row.progress * 100).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    language.installed -> TextButton(onClick = { onRemove(language.code) }) {
                        Text(stringResource(R.string.settings_language_remove), color = colors.onSurfaceVariant)
                    }
                    else -> TextButton(onClick = { onDownload(language.code) }) { Text(stringResource(R.string.settings_language_download)) }
                }
            }
            if (row.progress != null) {
                LinearProgressIndicator(
                    progress = { row.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest,
                )
            }
        }
    }
}
