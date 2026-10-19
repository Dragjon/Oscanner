package dev.olaf.oscanner.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.olaf.oscanner.core.model.AppSettings
import dev.olaf.oscanner.core.model.SortOrder
import dev.olaf.oscanner.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            themeMode = prefs[THEME].toEnum(ThemeMode.Dark),
            dynamicColor = prefs[DYNAMIC] ?: false,
            sortOrder = prefs[SORT].toEnum(SortOrder.Newest),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[DYNAMIC] = enabled }
    }

    suspend fun setSortOrder(order: SortOrder) {
        store.edit { it[SORT] = order.name }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val SORT = stringPreferencesKey("sort_order")
    }
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
