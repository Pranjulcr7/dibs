// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.settings.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    /**
     * Marks onboarding done, optionally recording the name. A null or blank
     * name is a skip, not an error — onboarding is skippable by design
     * (SPEC §4.1 screen 11) and everything works without a name.
     */
    fun complete(name: String?) {
        val trimmed = name?.trim().orEmpty()
        viewModelScope.launch {
            settingsStore.update {
                it.copy(
                    onboardingComplete = true,
                    displayName = trimmed.ifEmpty { it.displayName },
                )
            }
        }
    }
}
