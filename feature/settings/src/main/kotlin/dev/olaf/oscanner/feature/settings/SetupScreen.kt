package dev.olaf.oscanner.feature.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.data.SettingsRepository
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.design.endTypingOutside
import dev.olaf.oscanner.core.model.AppSettings
import dev.olaf.oscanner.core.model.CameraOrientation
import dev.olaf.oscanner.core.model.PageFilter
import dev.olaf.oscanner.core.model.PdfPageFormat
import dev.olaf.oscanner.core.model.PdfQuality
import dev.olaf.oscanner.core.model.SortOrder
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Choosing defaults after the welcome tour, a few at a time: how the app looks, how new scans
 * come out, how PDFs are made, and a few everyday things. Each choice is saved as it's made, so
 * the theme changes right away and leaving partway keeps what was chosen; [onDone] ends it
 * from any step. Everything here is in Settings too.
 */
@Composable
fun SetupScreen(
    settings: AppSettings,
    repository: SettingsRepository,
    /** False when this phone has no fingerprint, face or screen lock to use. */
    appLockAvailable: Boolean,
    /** Confirms with the system prompt, then calls back with whether it succeeded. */
    confirmLock: (onResult: (Boolean) -> Unit) -> Unit,
    onDone: () -> Unit,
) {
    val steps = 4
    val pager = rememberPagerState { steps }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val last = pager.currentPage == steps - 1
    fun save(block: suspend SettingsRepository.() -> Unit) {
        scope.launch { repository.block() }
    }
    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .endTypingOutside(),
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.setup_step, pager.currentPage + 1, steps),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDone) { Text(stringResource(R.string.setup_skip), color = colors.onSurfaceVariant) }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { step ->
                when (step) {
                    0 -> Step(R.string.setup_look_title, intro = true) {
                        SettingsCard {
                            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                            Spacer(Modifier.height(12.dp))
                            ThemeChoice(settings.themeMode) { save { setThemeMode(it) } }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            SwitchCard(
                                title = stringResource(R.string.settings_wallpaper_color),
                                description = stringResource(R.string.settings_wallpaper_color_description),
                                checked = settings.dynamicColor,
                                onCheckedChange = { save { setDynamicColor(it) } },
                            )
                        }
                    }
                    1 -> Step(R.string.setup_scans_title) { Scans(settings, ::save) }
                    2 -> Step(R.string.setup_pdf_title) { Pdfs(settings, ::save) }
                    else -> Step(R.string.setup_everyday_title) {
                        SwitchCard(
                            title = stringResource(R.string.settings_auto_read),
                            description = stringResource(R.string.settings_auto_read_description),
                            checked = settings.autoReadText,
                            onCheckedChange = { save { setAutoReadText(it) } },
                        )
                        SwitchCard(
                            title = stringResource(R.string.settings_app_lock),
                            description = stringResource(
                                if (appLockAvailable) R.string.settings_app_lock_description else R.string.settings_app_lock_unavailable,
                            ),
                            checked = settings.appLock,
                            onCheckedChange = { enable ->
                                // As in Settings: the lock proves it works before it's turned on.
                                if (appLockAvailable || settings.appLock) confirmLock { ok -> if (ok) save { setAppLock(enable) } }
                            },
                        )
                        SettingsCard {
                            Text(stringResource(R.string.setup_sort), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                            Spacer(Modifier.height(12.dp))
                            Choice(
                                options = listOf(
                                    SortOrder.Newest to R.string.setup_sort_newest,
                                    SortOrder.Oldest to R.string.setup_sort_oldest,
                                    SortOrder.Name to R.string.setup_sort_name,
                                ),
                                selected = settings.sortOrder,
                                onSelect = { save { setSortOrder(it) } },
                            )
                        }
                        if (settings.textLanguages == listOf("eng")) {
                            Text(
                                stringResource(R.string.setup_languages_later),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
            Dots(steps, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp))
            Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 20.dp)) {
                if (pager.currentPage > 0) {
                    TextButton(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) { Text(stringResource(R.string.setup_back), color = colors.onSurfaceVariant) }
                }
                PrimaryPill(
                    icon = if (last) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward,
                    label = stringResource(if (last) R.string.setup_done else R.string.setup_next),
                    onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun Step(title: Int, intro: Boolean = false, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onBackground,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp).semantics { heading() },
        )
        if (intro) {
            Text(
                stringResource(R.string.setup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        content()
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Scans(settings: AppSettings, save: (suspend SettingsRepository.() -> Unit) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fixes = settings.scanFixes
    SettingsCard {
        Text(stringResource(R.string.setup_scans_look), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.selectableGroup()) {
            listOf(
                null to (R.string.setup_look_usual to R.string.setup_look_usual_description),
                PageFilter.Auto to (R.string.setup_look_auto to R.string.setup_look_auto_description),
                PageFilter.Bright to (R.string.setup_look_bright to R.string.setup_look_bright_description),
                PageFilter.Gray to (R.string.setup_look_gray to R.string.setup_look_gray_description),
                PageFilter.Ink to (R.string.setup_look_ink to R.string.setup_look_ink_description),
                PageFilter.Original to (R.string.setup_look_original to R.string.setup_look_original_description),
            ).forEach { (look, text) ->
                Option(
                    title = stringResource(text.first),
                    description = stringResource(text.second),
                    selected = fixes.look == look,
                    onClick = { save { setScanFixes(fixes.copy(look = look)) } },
                )
            }
        }
    }
    Text(
        stringResource(R.string.setup_fixes),
        style = MaterialTheme.typography.titleSmall,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
    SwitchCard(
        title = stringResource(R.string.setup_fix_flatten),
        description = stringResource(R.string.setup_fix_flatten_description),
        checked = fixes.flatten,
        onCheckedChange = { save { setScanFixes(fixes.copy(flatten = it)) } },
    )
    SwitchCard(
        title = stringResource(R.string.setup_fix_edges),
        description = stringResource(R.string.setup_fix_edges_description),
        checked = fixes.fillEdges,
        onCheckedChange = { save { setScanFixes(fixes.copy(fillEdges = it)) } },
    )
    SwitchCard(
        title = stringResource(R.string.setup_fix_fingers),
        description = stringResource(R.string.setup_fix_fingers_description),
        checked = fixes.removeFingers,
        onCheckedChange = { save { setScanFixes(fixes.copy(removeFingers = it)) } },
    )
    SwitchCard(
        title = stringResource(R.string.setup_fix_timestamp),
        description = stringResource(R.string.setup_fix_timestamp_description),
        checked = fixes.timestamp,
        onCheckedChange = { save { setScanFixes(fixes.copy(timestamp = it)) } },
    )
    SettingsCard {
        Text(stringResource(R.string.setup_orientation), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        Spacer(Modifier.height(12.dp))
        Choice(
            options = listOf(
                CameraOrientation.Auto to R.string.setup_orientation_auto,
                CameraOrientation.Portrait to R.string.setup_orientation_portrait,
                CameraOrientation.Landscape to R.string.setup_orientation_landscape,
            ),
            selected = settings.cameraOrientation,
            onSelect = { save { setCameraOrientation(it) } },
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.setup_orientation_description), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun Pdfs(settings: AppSettings, save: (suspend SettingsRepository.() -> Unit) -> Unit) {
    val colors = MaterialTheme.colorScheme
    SettingsCard {
        Text(stringResource(R.string.settings_pdf_page), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        Spacer(Modifier.height(12.dp))
        Choice(
            options = listOf(
                PdfPageFormat.Auto to R.string.settings_pdf_page_auto,
                PdfPageFormat.A4 to R.string.settings_pdf_page_a4,
                PdfPageFormat.Letter to R.string.settings_pdf_page_letter,
            ),
            selected = settings.pdfPageFormat,
            onSelect = { save { setPdfPageFormat(it) } },
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.settings_pdf_page_description), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        val usual = stringResource(if (Locale.getDefault().country in LETTER_COUNTRIES) R.string.settings_pdf_page_letter else R.string.settings_pdf_page_a4)
        Text(stringResource(R.string.setup_paper_here, usual), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
    SettingsCard {
        Text(stringResource(R.string.settings_pdf_quality), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        Spacer(Modifier.height(12.dp))
        Choice(
            options = listOf(
                PdfQuality.Small to R.string.settings_pdf_quality_small,
                PdfQuality.Balanced to R.string.settings_pdf_quality_balanced,
                PdfQuality.Best to R.string.settings_pdf_quality_best,
            ),
            selected = settings.pdfQuality,
            onSelect = { save { setPdfQuality(it) } },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                when (settings.pdfQuality) {
                    PdfQuality.Small -> R.string.settings_pdf_quality_small_description
                    PdfQuality.Balanced -> R.string.settings_pdf_quality_balanced_description
                    PdfQuality.Best -> R.string.settings_pdf_quality_best_description
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
    SettingsCard {
        Text(stringResource(R.string.settings_watermark), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.settings_watermark_description), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        // Once typing starts, the field keeps its own text; storage catching up never overwrites it.
        var draft by rememberSaveable { mutableStateOf<String?>(null) }
        val focus = LocalFocusManager.current
        OutlinedTextField(
            value = draft ?: settings.watermark,
            onValueChange = {
                val text = it.take(60)
                draft = text
                save { setWatermark(text) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
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

/** One of a list of choices, with a line saying what it does; the whole row picks it. */
@Composable
private fun Option(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.primary))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

/** Where you are in the setup: the current dot stretches. */
@Composable
private fun Dots(count: Int, current: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.semantics { contentDescription = "${current + 1} / $count" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(width = if (i == current) 22.dp else 8.dp, height = 8.dp)
                    .background(if (i == current) colors.primary else colors.outlineVariant, CircleShape),
            )
        }
    }
}

/** Where Letter, not A4, is the usual paper. */
private val LETTER_COUNTRIES = setOf("US", "CA", "MX", "PH", "CL", "CO", "VE", "GT", "CR", "PA", "DO", "SV", "NI", "HN", "PR", "BZ")
