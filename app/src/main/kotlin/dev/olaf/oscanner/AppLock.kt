package dev.olaf.oscanner

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.olaf.oscanner.core.design.PrimaryPill

/**
 * Fingerprint, face or the phone's own PIN/pattern/password, through the system prompt.
 * Locks when the app opens and after it has been away for [RELOCK_AFTER_MS]; a quick trip
 * to the share sheet or the file picker doesn't count.
 */
class AppLock(private val activity: FragmentActivity) {

    private var backgroundedAt = 0L

    /** True when the phone has something to unlock with. */
    fun available(): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    /** Whether coming back to the foreground should lock again. */
    fun shouldRelock(): Boolean =
        backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS

    /** Shows the system prompt; [onResult] gets true once the person is confirmed. */
    fun prompt(onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.app_lock_prompt_title))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }

    private companion object {
        /** Weak biometrics or the screen lock: the same bar as unlocking the phone. */
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        const val RELOCK_AFTER_MS = 60_000L
    }
}

/** Covers everything while locked; asks straight away, and again on tap. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.app_lock_locked),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryPill(Icons.Rounded.LockOpen, stringResource(R.string.app_lock_unlock), onUnlock)
    }
}
