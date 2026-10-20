package dev.olaf.oscanner

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.design.OscannerTheme
import dev.olaf.oscanner.core.design.isDarkTheme
import dev.olaf.oscanner.core.model.ThemeMode

/** A FragmentActivity because the system biometric prompt is shown through fragments. */
class MainActivity : FragmentActivity() {

    /** Images shared into the app, waiting for the nav host to import them. */
    private val sharedImages = mutableStateOf<List<Uri>?>(null)
    /** Set when opened from the Scan shortcut, tile or widget. */
    private val scanRequested = mutableStateOf(false)
    private val appLock by lazy { AppLock(this) }
    /** True until the person unlocks, when App lock is on. Starts locked so nothing shows first. */
    private val locked = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as OscannerApplication).container
        if (savedInstanceState == null) {
            sharedImages.value = intent.sharedImages()
            scanRequested.value = intent.action == ACTION_SCAN
        }

        setContent {
            // Null until the stored settings are read, so a locked app never flashes its content.
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val dark = isDarkTheme(settings?.themeMode ?: ThemeMode.Dark)
            val lockOn = settings?.appLock == true

            DisposableEffect(dark) {
                // System bar icons follow our theme setting, not the phone's.
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            val loaded = settings != null
            LaunchedEffect(loaded, lockOn) {
                // Only once the real settings are in: before that, "off" is just the default.
                if (!loaded) return@LaunchedEffect
                // With the lock on, the recent-apps preview stays blank too.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!lockOn)
                if (!lockOn) locked.value = false
            }

            OscannerTheme(themeMode = settings?.themeMode ?: ThemeMode.Dark, dynamicColor = settings?.dynamicColor == true) {
                when {
                    settings == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    lockOn && locked.value -> LockScreen(onUnlock = { appLock.prompt { ok -> if (ok) locked.value = false } })
                    else -> OscannerNavHost(
                        container = container,
                        sharedImages = sharedImages.value,
                        onSharedImagesHandled = { sharedImages.value = null },
                        scanRequested = scanRequested.value,
                        onScanHandled = { scanRequested.value = false },
                        appLockAvailable = appLock.available(),
                        confirmLock = appLock::prompt,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (appLock.shouldRelock()) locked.value = true
    }

    override fun onStop() {
        super.onStop()
        appLock.onBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.sharedImages()?.let { sharedImages.value = it }
        if (intent.action == ACTION_SCAN) scanRequested.value = true
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
