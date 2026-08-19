// SPDX-License-Identifier: Apache-2.0
package app.dibs

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.dibs.core.ui.theme.DibsTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * FragmentActivity (not ComponentActivity) because androidx.biometric requires
 * one for the app-lock prompt (FR-T3).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Re-lock whenever the app leaves the foreground with the lock enabled.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) viewModel.onBackgrounded()
            },
        )

        setContent {
            val state by viewModel.uiState.collectAsState()
            val locked by viewModel.locked.collectAsState()
            when (val current = state) {
                // A blank first frame beats flashing the wrong screen while the
                // stored settings load (they arrive within a frame or two).
                MainUiState.Loading -> Unit
                is MainUiState.Ready -> {
                    applySecureFlag(current.settings.blockScreenshots)
                    if (locked && current.settings.appLockEnabled) {
                        DibsTheme(
                            themeMode = current.settings.themeMode,
                            dynamicColor = current.settings.dynamicColor,
                        ) {
                            LockedScreen(onUnlock = ::showBiometricPrompt)
                        }
                    } else {
                        DibsApp(current.settings, initialRoute = intent.getStringExtra(RecurringWorker.EXTRA_ROUTE))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.shouldPromptUnlock()) showBiometricPrompt()
    }

    /** Edge 73: FLAG_SECURE is opt-in; it also blanks the recents preview. */
    private fun applySecureFlag(enabled: Boolean) {
        if (enabled) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun showBiometricPrompt() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            // No credential set up at all: locking would brick the app; unlock.
            viewModel.unlock()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.unlock()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_lock_title))
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }
}

@androidx.compose.runtime.Composable
private fun LockedScreen(onUnlock: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = onUnlock) {
                Text(stringResource(R.string.app_lock_unlock))
            }
        }
    }
}
