package dev.olaf.oscanner

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.design.OscannerTheme
import dev.olaf.oscanner.core.design.isDarkTheme
import dev.olaf.oscanner.core.model.AppSettings

class MainActivity : ComponentActivity() {

    /** Images shared into the app, waiting for the nav host to import them. */
    private val sharedImages = mutableStateOf<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as OscannerApplication).container
        if (savedInstanceState == null) sharedImages.value = intent.sharedImages()

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val dark = isDarkTheme(settings.themeMode)

            // System bar icons follow our theme setting, not the phone's.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }

            OscannerTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                OscannerNavHost(
                    container = container,
                    sharedImages = sharedImages.value,
                    onSharedImagesHandled = { sharedImages.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.sharedImages()?.let { sharedImages.value = it }
    }
}

private fun Intent.sharedImages(): List<Uri>? {
    val uris = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(stream())
        Intent.ACTION_SEND_MULTIPLE -> streams()
        else -> return null
    }
    return uris.takeIf { it.isNotEmpty() }
}

@Suppress("DEPRECATION")
private fun Intent.stream(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

@Suppress("DEPRECATION")
private fun Intent.streams(): List<Uri> =
    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableArrayListExtra(Intent.EXTRA_STREAM))
        .orEmpty()
