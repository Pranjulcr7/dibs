// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.settings.SettingsStore
import app.dibs.core.domain.settings.ThemeMode
import app.dibs.core.domain.settings.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val settings: StateFlow<UserSettings?> = settingsStore.settings
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    fun setDisplayName(name: String) = update { it.copy(displayName = name.trim()) }

    fun setDefaultCurrency(currency: CurrencyCode) = update { it.copy(defaultCurrency = currency) }

    fun setAppLock(enabled: Boolean) = update { it.copy(appLockEnabled = enabled) }

    fun setBlockScreenshots(enabled: Boolean) = update { it.copy(blockScreenshots = enabled) }

    private fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
