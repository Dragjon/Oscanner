package dev.olaf.oscanner.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.olaf.oscanner.core.model.AppSettings
import dev.olaf.oscanner.core.model.CameraOrientation
import dev.olaf.oscanner.core.model.PageFilter
import dev.olaf.oscanner.core.model.ScanFixes
import dev.olaf.oscanner.core.model.PdfPageFormat
import dev.olaf.oscanner.core.model.PdfQuality
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
            autoReadText = prefs[AUTO_READ] ?: true,
            textLanguages = prefs[LANGUAGES]?.split(',')?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOf("eng"),
            pdfPageFormat = prefs[PDF_FORMAT].toEnum(PdfPageFormat.Auto),
            pdfQuality = prefs[PDF_QUALITY].toEnum(PdfQuality.Balanced),
            watermark = prefs[WATERMARK].orEmpty(),
            appLock = prefs[APP_LOCK] ?: false,
            scanFixes = ScanFixes(
                removeHandwriting = prefs[FIX_HANDWRITING] ?: false,
                removeFingers = prefs[FIX_FINGERS] ?: false,
                flatten = prefs[FIX_FLATTEN] ?: true,
                descreen = prefs[FIX_DESCREEN] ?: false,
                look = prefs[FIX_LOOK]?.let { name -> PageFilter.entries.firstOrNull { it.name == name } },
                timestamp = prefs[FIX_TIMESTAMP] ?: false,
                fillEdges = prefs[FIX_EDGES] ?: false,
            ),
            cameraOrientation = prefs[CAMERA_ORIENTATION].toEnum(CameraOrientation.Auto),
            welcomed = prefs[WELCOMED] ?: false,
            // People who saw the tour before there was a setup have chosen in Settings already.
            setUp = prefs[SET_UP] ?: prefs[WELCOMED] ?: false,
        )
    }

    suspend fun setScanFixes(fixes: ScanFixes) {
        store.edit {
            it[FIX_HANDWRITING] = fixes.removeHandwriting
            it[FIX_FINGERS] = fixes.removeFingers
            it[FIX_FLATTEN] = fixes.flatten
            it[FIX_DESCREEN] = fixes.descreen
            it[FIX_TIMESTAMP] = fixes.timestamp
            it[FIX_EDGES] = fixes.fillEdges
            val look = fixes.look
            if (look == null) it.remove(FIX_LOOK) else it[FIX_LOOK] = look.name
        }
    }

    suspend fun setCameraOrientation(orientation: CameraOrientation) {
        store.edit { it[CAMERA_ORIENTATION] = orientation.name }
    }

    /** The tour is done; the setup comes next, unless defaults were already chosen. */
    suspend fun setWelcomed() {
        store.edit {
            if (it[SET_UP] == null) it[SET_UP] = false
            it[WELCOMED] = true
        }
    }

    suspend fun setSetUp() {
        store.edit { it[SET_UP] = true }
    }

    suspend fun setAppLock(enabled: Boolean) {
        store.edit { it[APP_LOCK] = enabled }
    }

    suspend fun setWatermark(text: String) {
        store.edit { it[WATERMARK] = text.take(60) }
    }

    suspend fun setPdfPageFormat(format: PdfPageFormat) {
        store.edit { it[PDF_FORMAT] = format.name }
    }

    suspend fun setPdfQuality(quality: PdfQuality) {
        store.edit { it[PDF_QUALITY] = quality.name }
    }

    suspend fun setAutoReadText(enabled: Boolean) {
        store.edit { it[AUTO_READ] = enabled }
    }

    suspend fun setTextLanguages(codes: List<String>) {
        store.edit { it[LANGUAGES] = codes.distinct().joinToString(",") }
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
        val AUTO_READ = booleanPreferencesKey("auto_read_text")
        val LANGUAGES = stringPreferencesKey("text_languages")
        val PDF_FORMAT = stringPreferencesKey("pdf_page_format")
        val PDF_QUALITY = stringPreferencesKey("pdf_quality")
        val WATERMARK = stringPreferencesKey("watermark")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val FIX_HANDWRITING = booleanPreferencesKey("fix_handwriting")
        val FIX_FINGERS = booleanPreferencesKey("fix_fingers")
        // A new name, so everyone starts with flattening on now that it leaves flat pages alone.
        val FIX_FLATTEN = booleanPreferencesKey("fix_flatten_on")
        val FIX_DESCREEN = booleanPreferencesKey("fix_descreen")
        val FIX_LOOK = stringPreferencesKey("fix_look")
        val FIX_TIMESTAMP = booleanPreferencesKey("fix_timestamp")
        val FIX_EDGES = booleanPreferencesKey("fix_edges")
        val WELCOMED = booleanPreferencesKey("welcomed")
        val SET_UP = booleanPreferencesKey("set_up")
        val CAMERA_ORIENTATION = stringPreferencesKey("camera_orientation")
    }
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
