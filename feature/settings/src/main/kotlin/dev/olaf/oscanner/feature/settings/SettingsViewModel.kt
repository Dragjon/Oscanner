package dev.olaf.oscanner.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.SampleData
import dev.olaf.oscanner.core.data.SettingsRepository
import dev.olaf.oscanner.core.model.AppSettings
import dev.olaf.oscanner.core.model.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    /** Only passed in debug builds; null hides the developer section. */
    private val sampleData: SampleData?,
) : ViewModel() {

    val state: StateFlow<AppSettings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val showDeveloperTools: Boolean get() = sampleData != null

    fun onThemeModeChange(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun onDynamicColorChange(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    fun fillSampleLibrary(onDone: () -> Unit) {
        val data = sampleData ?: return
        viewModelScope.launch {
            data.populate()
            onDone()
        }
    }

    fun clearLibrary(onDone: () -> Unit) {
        val data = sampleData ?: return
        viewModelScope.launch {
            data.clear()
            onDone()
        }
    }
}
