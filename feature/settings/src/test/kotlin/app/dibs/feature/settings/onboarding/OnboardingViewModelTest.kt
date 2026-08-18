// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.settings.onboarding

import app.dibs.core.domain.settings.SettingsStore
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

class FakeSettingsStore : SettingsStore {
    private val state = MutableStateFlow(UserSettings())
    override val settings: Flow<UserSettings> = state
    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        state.value = transform(state.value)
    }

    val current: UserSettings get() = state.value
}

class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: FakeSettingsStore
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = FakeSettingsStore()
        viewModel = OnboardingViewModel(store)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `given a name entered when onboarding completes then the name and completion flag persist`() = runTest(dispatcher) {
        viewModel.complete("  Priya  ")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(store.current.onboardingComplete)
        assertEquals("Priya", store.current.displayName)
    }

    @Test
    fun `given onboarding skipped when completed with no name then the flag persists and the name stays empty`() = runTest(dispatcher) {
        viewModel.complete(null)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(store.current.onboardingComplete)
        assertEquals("", store.current.displayName)
    }

    @Test
    fun `given a blank name when completed then the stored name stays empty rather than whitespace`() = runTest(dispatcher) {
        viewModel.complete("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(store.current.onboardingComplete)
        assertEquals("", store.current.displayName)
    }
}
