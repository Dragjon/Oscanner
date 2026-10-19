package dev.olaf.oscanner

import android.app.Application
import dev.olaf.oscanner.core.data.DataContainer

class OscannerApplication : Application() {
    val container by lazy { DataContainer(this) }
}
