// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.settings

import app.dibs.core.domain.money.CurrencyCode
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsViewModelTest {

    private class Store : SettingsStore {
        val state = MutableStateFlow(UserSettings())
        override val settings: Flow<UserSettings> = state
        override suspend fun update(transform: (UserSettings) -> UserSettings) {
            state.value = transform(state.value)
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: Store
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = Store()
        viewModel = SettingsViewModel(store)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `given every settings action then each persists its field and nothing else`() = runTest(dispatcher) {
        viewModel.setThemeMode(ThemeMode.DARK)
        viewModel.setDynamicColor(false)
        viewModel.setDisplayName("  Priya  ")
        viewModel.setDefaultCurrency(CurrencyCode("INR"))
        viewModel.setAppLock(true)
        viewModel.setBlockScreenshots(true)
        dispatcher.scheduler.advanceUntilIdle()

        val settings = store.state.value
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(false, settings.dynamicColor)
        assertEquals("Priya", settings.displayName)
        assertEquals(CurrencyCode("INR"), settings.defaultCurrency)
        assertTrue(settings.appLockEnabled)
        assertTrue(settings.blockScreenshots)
        // Untouched fields stay default.
        assertEquals(false, settings.onboardingComplete)
    }
}
