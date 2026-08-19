// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.backup.PassphraseStrength
import app.dibs.core.domain.ledger.BackupService
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.settings.SettingsStore
import app.dibs.core.domain.settings.ThemeMode
import app.dibs.core.domain.settings.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/** One-shot result of a backup or restore, surfaced as a toast. */
sealed interface BackupEvent {
    data object BackedUp : BackupEvent

    data object BackupFailed : BackupEvent

    data class Restored(val groups: Int, val opsAdded: Int) : BackupEvent

    /** Wrong passphrase or a damaged file — indistinguishable by design (edge 58). */
    data object RestoreRejected : BackupEvent

    data object RestoreFailed : BackupEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val backupService: BackupService,
) : ViewModel() {

    val settings: StateFlow<UserSettings?> = settingsStore.settings
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private val _backupEvents = MutableSharedFlow<BackupEvent>()
    val backupEvents: SharedFlow<BackupEvent> = _backupEvents.asSharedFlow()

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    fun setDisplayName(name: String) = update { it.copy(displayName = name.trim()) }

    fun setDefaultCurrency(currency: CurrencyCode) = update { it.copy(defaultCurrency = currency) }

    fun setAppLock(enabled: Boolean) = update { it.copy(appLockEnabled = enabled) }

    fun setBlockScreenshots(enabled: Boolean) = update { it.copy(blockScreenshots = enabled) }

    /**
     * FR-I3: encrypt everything and stream it to the SAF destination the user
     * picked. Weak passphrases are refused here too (edge 77) — the dialog
     * already blocks them, but the meter is UI and this is the enforcement.
     */
    fun backUp(passphrase: CharArray, open: () -> OutputStream?) {
        viewModelScope.launch {
            if (PassphraseStrength.assess(passphrase) == PassphraseStrength.Level.WEAK) {
                _backupEvents.emit(BackupEvent.BackupFailed)
                return@launch
            }
            val event = try {
                val bytes = backupService.createBackup(passphrase)
                withContext(Dispatchers.IO) {
                    (open() ?: throw IOException("destination unavailable")).use { it.write(bytes) }
                }
                BackupEvent.BackedUp
            } catch (_: IOException) {
                BackupEvent.BackupFailed
            }
            _backupEvents.emit(event)
        }
    }

    /** FR-I4: restore is a merge; nothing is written unless the file authenticates. */
    fun restore(passphrase: CharArray, open: () -> InputStream?) {
        viewModelScope.launch {
            val event = try {
                val bytes = withContext(Dispatchers.IO) {
                    (open() ?: throw IOException("source unavailable")).use { it.readBytes() }
                }
                when (val result = backupService.restoreBackup(bytes, passphrase)) {
                    is BackupService.RestoreResult.Restored ->
                        BackupEvent.Restored(result.groups, result.opsAdded)
                    BackupService.RestoreResult.WrongPassphraseOrCorrupt ->
                        BackupEvent.RestoreRejected
                }
            } catch (_: IOException) {
                BackupEvent.RestoreFailed
            }
            _backupEvents.emit(event)
        }
    }

    private fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
