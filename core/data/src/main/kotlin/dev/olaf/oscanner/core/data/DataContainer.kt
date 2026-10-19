package dev.olaf.oscanner.core.data

import android.content.Context
import dev.olaf.oscanner.core.data.db.OscannerDatabase
import dev.olaf.oscanner.core.ocr.Languages

/** Hand-wired dependencies, created once by the Application. */
class DataContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database by lazy { OscannerDatabase.create(appContext) }

    val documents by lazy { DocumentRepository(database) }
    val folders by lazy { FolderRepository(database) }
    val settings by lazy { SettingsRepository(appContext) }
    val languages by lazy { Languages(appContext) }
    val text by lazy { TextReader(appContext, database, settings, languages) }
    val pages by lazy { PageStore(appContext, database, text) }
    val sampleData by lazy { SampleData(appContext, database) }
}
