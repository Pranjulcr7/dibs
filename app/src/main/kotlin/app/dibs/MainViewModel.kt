// SPDX-License-Identifier: Apache-2.0
package app.dibs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.settings.SettingsStore
import app.dibs.core.domain.settings.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Loading exists so the first frame never flashes the wrong screen: the
 * onboarding-versus-home decision waits for the real stored settings.
 */
sealed interface MainUiState {
    data object Loading : MainUiState

    data class Ready(val settings: UserSettings) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = settingsStore.settings
        .map<UserSettings, MainUiState>(MainUiState::Ready)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), MainUiState.Loading)

    /** FR-T3 app-lock gate: locked from cold start and after backgrounding. */
    private val _locked = kotlinx.coroutines.flow.MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked

    fun unlock() {
        _locked.value = false
    }

    fun onBackgrounded() {
        _locked.value = true
    }

    /** Prompt only when the lock is enabled and currently engaged. */
    fun shouldPromptUnlock(): Boolean {
        val settings = (uiState.value as? MainUiState.Ready)?.settings ?: return false
        return settings.appLockEnabled && _locked.value
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
