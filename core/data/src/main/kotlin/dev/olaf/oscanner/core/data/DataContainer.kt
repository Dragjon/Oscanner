package dev.olaf.oscanner.core.data

import android.content.Context
import dev.olaf.oscanner.core.data.db.OscannerDatabase

/** Hand-wired dependencies, created once by the Application. */
class DataContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database by lazy { OscannerDatabase.create(appContext) }

    val documents by lazy { DocumentRepository(database) }
    val folders by lazy { FolderRepository(database) }
    val settings by lazy { SettingsRepository(appContext) }
    val sampleData by lazy { SampleData(appContext, database) }
}
