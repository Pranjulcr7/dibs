// SPDX-License-Identifier: Apache-2.0
package app.dibs

import app.cash.turbine.test
import app.dibs.core.domain.settings.SettingsStore
import app.dibs.core.domain.settings.ThemeMode
import app.dibs.core.domain.settings.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MainViewModelTest {

    private class FakeStore(initial: UserSettings) : SettingsStore {
        val state = MutableStateFlow(initial)
        override val settings: Flow<UserSettings> = state
        override suspend fun update(transform: (UserSettings) -> UserSettings) {
            state.value = transform(state.value)
        }
    }

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `given settings not yet loaded then the state starts as Loading and resolves to Ready`() = runTest(dispatcher) {
        val store = FakeStore(UserSettings(onboardingComplete = true, themeMode = ThemeMode.DARK))
        val viewModel = MainViewModel(store)

        viewModel.uiState.test {
            assertEquals(MainUiState.Loading, awaitItem())
            val ready = awaitItem() as MainUiState.Ready
            assertEquals(true, ready.settings.onboardingComplete)
            assertEquals(ThemeMode.DARK, ready.settings.themeMode)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `given onboarding completes while the app is open then the state updates without a restart`() = runTest(dispatcher) {
        val store = FakeStore(UserSettings(onboardingComplete = false))
        val viewModel = MainViewModel(store)

        viewModel.uiState.test {
            assertEquals(MainUiState.Loading, awaitItem())
            assertEquals(false, (awaitItem() as MainUiState.Ready).settings.onboardingComplete)

            store.update { it.copy(onboardingComplete = true) }
            assertEquals(true, (awaitItem() as MainUiState.Ready).settings.onboardingComplete)
            cancelAndConsumeRemainingEvents()
        }
    }
}
