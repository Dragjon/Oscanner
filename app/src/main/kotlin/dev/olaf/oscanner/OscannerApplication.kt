package dev.olaf.oscanner

import android.app.Application
import dev.olaf.oscanner.core.data.DataContainer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class OscannerApplication : Application() {
    val container by lazy { DataContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Housekeeping: Trash older than 30 days, and share files from earlier sessions.
        MainScope().launch {
            container.pages.purgeTrash()
            container.pages.clearExports()
        }
    }
}
