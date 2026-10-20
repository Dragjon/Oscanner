package dev.olaf.oscanner.feature.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import dev.olaf.oscanner.core.design.R as DesignR
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.design.SheetAction
import dev.olaf.oscanner.core.model.AppSettings
import dev.olaf.oscanner.core.model.PdfPageFormat
import dev.olaf.oscanner.core.model.PdfQuality
import dev.olaf.oscanner.core.model.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    versionName: String,
    onBack: () -> Unit,
    onOpenLanguages: () -> Unit,
    /** False when this phone has no fingerprint, face or screen lock to use. */
    appLockAvailable: Boolean,
    /** Confirms with the system prompt, then calls back with whether it succeeded. */
    confirmLock: (onResult: (Boolean) -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val filled = stringResource(R.string.settings_sample_filled)
    val cleared = stringResource(R.string.settings_library_cleared)
    SettingsScreen(
        state = state,
        versionName = versionName,
        showDeveloperTools = viewModel.showDeveloperTools,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onThemeModeChange = viewModel::onThemeModeChange,
        onDynamicColorChange = viewModel::onDynamicColorChange,
        onAutoReadTextChange = viewModel::onAutoReadTextChange,
        onOpenLanguages = onOpenLanguages,
        onPdfPageFormatChange = viewModel::onPdfPageFormatChange,
        onPdfQualityChange = viewModel::onPdfQualityChange,
        onWatermarkChange = viewModel::onWatermarkChange,
        appLockAvailable = appLockAvailable,
        onAppLockChange = { enable ->
            // Turning the lock on proves it works first; turning it off also asks, so a passer-by can't.
            confirmLock { ok -> if (ok) viewModel.onAppLockChange(enable) }
        },
        onFillSampleLibrary = {
            viewModel.fillSampleLibrary { scope.launch { snackbarHostState.showSnackbar(filled) } }
        },
        onClearLibrary = {
            viewModel.clearLibrary { scope.launch { snackbarHostState.showSnackbar(cleared) } }
        },
    )
}

@Composable
fun SettingsScreen(
    state: AppSettings,
    versionName: String,
    showDeveloperTools: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onAutoReadTextChange: (Boolean) -> Unit,
    onOpenLanguages: () -> Unit,
    onPdfPageFormatChange: (PdfPageFormat) -> Unit,
    onPdfQualityChange: (PdfQuality) -> Unit,
    onWatermarkChange: (String) -> Unit,
    appLockAvailable: Boolean,
    onAppLockChange: (Boolean) -> Unit,
    onFillSampleLibrary: () -> Unit,
    onClearLibrary: () -> Unit,
) {
    val statusBar = WindowInsets.statusBars.asPaddingValues()
    val navBar = WindowInsets.navigationBars.asPaddingValues()
    val colors = MaterialTheme.colorScheme

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        LazyColumn(
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize(),
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
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                            tint = colors.onBackground,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.onBackground,
                    )
                }
            }

            item(key = "appearance") { SectionLabel(stringResource(R.string.settings_appearance)) }
            item(key = "theme") {
                SettingsCard {
                    Text(
                        stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    ThemeChoice(state.themeMode, onThemeModeChange)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item(key = "dynamic") {
                    SwitchCard(
                        title = stringResource(R.string.settings_wallpaper_color),
                        description = stringResource(R.string.settings_wallpaper_color_description),
                        checked = state.dynamicColor,
                        onCheckedChange = onDynamicColorChange,
                    )
                }
            }

            item(key = "privacy") { SectionLabel(stringResource(R.string.settings_privacy)) }
            item(key = "app-lock") {
                SwitchCard(
                    title = stringResource(R.string.settings_app_lock),
                    description = stringResource(
                        if (appLockAvailable) R.string.settings_app_lock_description else R.string.settings_app_lock_unavailable,
                    ),
                    checked = state.appLock,
                    onCheckedChange = { if (appLockAvailable || state.appLock) onAppLockChange(it) },
                )
            }

            item(key = "text") { SectionLabel(stringResource(R.string.settings_text)) }
            item(key = "auto-read") {
                SwitchCard(
                    title = stringResource(R.string.settings_auto_read),
                    description = stringResource(R.string.settings_auto_read_description),
                    checked = state.autoReadText,
                    onCheckedChange = onAutoReadTextChange,
                )
            }
            item(key = "languages") {
                SheetAction(
                    icon = Icons.Rounded.Translate,
                    title = stringResource(R.string.settings_languages),
                    description = stringResource(R.string.settings_languages_description),
                    onClick = onOpenLanguages,
                )
            }

            item(key = "pdf") { SectionLabel(stringResource(R.string.settings_pdf)) }
            item(key = "pdf-size") {
                SettingsCard {
                    Text(stringResource(R.string.settings_pdf_page), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Spacer(Modifier.height(12.dp))
                    Choice(
                        options = listOf(
                            PdfPageFormat.Auto to R.string.settings_pdf_page_auto,
                            PdfPageFormat.A4 to R.string.settings_pdf_page_a4,
                            PdfPageFormat.Letter to R.string.settings_pdf_page_letter,
                        ),
                        selected = state.pdfPageFormat,
                        onSelect = onPdfPageFormatChange,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_pdf_page_description), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            item(key = "pdf-quality") {
                SettingsCard {
                    Text(stringResource(R.string.settings_pdf_quality), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Spacer(Modifier.height(12.dp))
                    Choice(
                        options = listOf(
                            PdfQuality.Small to R.string.settings_pdf_quality_small,
                            PdfQuality.Balanced to R.string.settings_pdf_quality_balanced,
                            PdfQuality.Best to R.string.settings_pdf_quality_best,
                        ),
                        selected = state.pdfQuality,
                        onSelect = onPdfQualityChange,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            when (state.pdfQuality) {
                                PdfQuality.Small -> R.string.settings_pdf_quality_small_description
                                PdfQuality.Balanced -> R.string.settings_pdf_quality_balanced_description
                                PdfQuality.Best -> R.string.settings_pdf_quality_best_description
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            item(key = "watermark") {
                SettingsCard {
                    Text(stringResource(R.string.settings_watermark), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.settings_watermark_description), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    // Once typing starts, the field keeps its own text; storage catching up never overwrites it.
                    var draft by rememberSaveable { mutableStateOf<String?>(null) }
                    OutlinedTextField(
                        value = draft ?: state.watermark,
                        onValueChange = {
                            val text = it.take(60)
                            draft = text
                            onWatermarkChange(text)
                        },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.settings_watermark_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.onSurfaceVariant,
                            unfocusedBorderColor = colors.outlineVariant,
                            cursorColor = colors.primary,
                        ),
                    )
                }
            }

            item(key = "about") { SectionLabel(stringResource(R.string.settings_about)) }
            item(key = "about-card") {
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Decorative: the name right beside it says the same.
                        Image(painterResource(DesignR.drawable.oscanner_logo), contentDescription = null, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                stringResource(R.string.settings_app_name_version, versionName),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.settings_about_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.settings_license),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            if (showDeveloperTools) {
                item(key = "developer") { SectionLabel(stringResource(R.string.settings_developer)) }
                item(key = "fill") {
                    SheetAction(
                        icon = Icons.Rounded.LibraryAdd,
                        title = stringResource(R.string.settings_fill_sample),
                        description = stringResource(R.string.settings_fill_sample_description),
                        onClick = onFillSampleLibrary,
                    )
                }
                item(key = "clear") {
                    SheetAction(
                        icon = Icons.Rounded.DeleteSweep,
                        title = stringResource(R.string.settings_clear_library),
                        description = stringResource(R.string.settings_clear_library_description),
                        onClick = onClearLibrary,
                    )
                }
            }
        }

        SnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navBar.calculateBottomPadding() + 16.dp)
                .widthIn(max = 480.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun ThemeChoice(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) = Choice(
    options = listOf(
        ThemeMode.Dark to R.string.settings_theme_dark,
        ThemeMode.Light to R.string.settings_theme_light,
        ThemeMode.System to R.string.settings_theme_system,
    ),
    selected = selected,
    onSelect = onSelect,
)

/**
 * A row of mutually exclusive choices, each a plain word. No checkmark: it took a third of a
 * narrow segment and pushed "Match phone" onto two lines. The chosen one is filled and bold.
 * Every segment takes the height of the tallest, so a label that does wrap doesn't stand out.
 */
@Composable
private fun <T> Choice(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                modifier = Modifier.fillMaxHeight(),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.primaryContainer,
                    activeContentColor = colors.onPrimaryContainer,
                    inactiveContainerColor = colors.surfaceContainerHigh,
                    inactiveContentColor = colors.onSurfaceVariant,
                    activeBorderColor = colors.outlineVariant,
                    inactiveBorderColor = colors.outlineVariant,
                ),
            ) {
                // Two lines rather than cut off, for long words and large text.
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (value == selected) FontWeight.Bold else null,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A plain-sentence setting; the whole card toggles, not just the switch. */
@Composable
private fun SwitchCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.Switch },
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 72.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimary,
                    checkedTrackColor = colors.primary,
                    uncheckedThumbColor = colors.onSurfaceVariant,
                    uncheckedTrackColor = colors.surfaceContainerHigh,
                    uncheckedBorderColor = colors.outline,
                ),
                modifier = Modifier.size(width = 52.dp, height = 32.dp),
            )
        }
    }
}
