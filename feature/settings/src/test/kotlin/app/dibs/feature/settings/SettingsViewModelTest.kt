// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.settings

import app.cash.turbine.test
import app.dibs.core.domain.ledger.BackupService
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

    private class FakeBackupService : BackupService {
        var created = 0
        var restoreResult: BackupService.RestoreResult =
            BackupService.RestoreResult.Restored(groups = 1, opsAdded = 2, identityAdopted = false)

        override suspend fun createBackup(passphrase: CharArray): ByteArray {
            created++
            return byteArrayOf(1, 2, 3)
        }

        override suspend fun restoreBackup(bytes: ByteArray, passphrase: CharArray) = restoreResult
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: Store
    private lateinit var backup: FakeBackupService
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = Store()
        backup = FakeBackupService()
        viewModel = SettingsViewModel(store, backup)
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

    @Test
    fun `given a weak passphrase then backup is refused even if the dialog were bypassed`() = runTest(dispatcher) {
        viewModel.backupEvents.test {
            viewModel.backUp("short".toCharArray()) { java.io.ByteArrayOutputStream() }
            assertEquals(BackupEvent.BackupFailed, awaitItem())
            assertEquals(0, backup.created)
        }
    }

    @Test
    fun `given a strong passphrase then the encrypted bytes land in the chosen stream`() = runTest(dispatcher) {
        val sink = java.io.ByteArrayOutputStream()
        viewModel.backupEvents.test {
            viewModel.backUp("correct horse battery staple".toCharArray()) { sink }
            assertEquals(BackupEvent.BackedUp, awaitItem())
        }
        assertEquals(1, backup.created)
        assertEquals(3, sink.size())
    }

    @Test
    fun `given restore outcomes then each maps to its own user-visible event`() = runTest(dispatcher) {
        viewModel.backupEvents.test {
            viewModel.restore("pw".toCharArray()) { java.io.ByteArrayInputStream(byteArrayOf(9)) }
            assertEquals(BackupEvent.Restored(groups = 1, opsAdded = 2), awaitItem())

            backup.restoreResult = BackupService.RestoreResult.WrongPassphraseOrCorrupt
            viewModel.restore("pw".toCharArray()) { java.io.ByteArrayInputStream(byteArrayOf(9)) }
            assertEquals(BackupEvent.RestoreRejected, awaitItem())

            viewModel.restore("pw".toCharArray()) { null }
            assertEquals(BackupEvent.RestoreFailed, awaitItem())
        }
    }
}
