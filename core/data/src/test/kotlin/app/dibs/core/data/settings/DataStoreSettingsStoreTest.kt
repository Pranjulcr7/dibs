// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.settings

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.settings.ThemeMode
import app.dibs.core.domain.settings.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun withStore(file: File, block: suspend (DataStoreSettingsStore) -> Unit) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = DataStoreFactory.create(
            serializer = UserSettingsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { UserSettings() },
            scope = scope,
        ) { file }
        try {
            runBlocking { block(DataStoreSettingsStore(dataStore)) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `given a fresh install when settings are read then the defaults apply`() {
        withStore(File(tmp.root, "s1.json")) { store ->
            val settings = store.settings.first()
            assertEquals(UserSettings(), settings)
            assertEquals(false, settings.onboardingComplete)
            assertEquals(CurrencyCode("USD"), settings.defaultCurrency)
        }
    }

    @Test
    fun `given an update when the store is reopened then the settings survive`() {
        val file = File(tmp.root, "s2.json")
        withStore(file) { store ->
            store.update {
                it.copy(
                    onboardingComplete = true,
                    displayName = "Priya",
                    themeMode = ThemeMode.DARK,
                    dynamicColor = false,
                    defaultCurrency = CurrencyCode("INR"),
                )
            }
        }
        withStore(file) { store ->
            val settings = store.settings.first()
            assertEquals("Priya", settings.displayName)
            assertEquals(true, settings.onboardingComplete)
            assertEquals(ThemeMode.DARK, settings.themeMode)
            assertEquals(false, settings.dynamicColor)
            assertEquals(CurrencyCode("INR"), settings.defaultCurrency)
        }
    }

    @Test
    fun `given a corrupted settings file when read then it recovers to defaults instead of crashing`() {
        val file = File(tmp.root, "s3.json")
        withStore(file) { store -> store.update { it.copy(displayName = "X") } }
        file.writeText("{ not json !!!")
        withStore(file) { store ->
            assertEquals(UserSettings(), store.settings.first())
        }
    }

    @Test
    fun `given a settings file from a newer app version with unknown fields then known fields still load`() {
        val file = File(tmp.root, "s4.json")
        file.writeText("""{"displayName":"Kai","onboardingComplete":true,"someFutureField":42}""")
        withStore(file) { store ->
            val settings = store.settings.first()
            assertEquals("Kai", settings.displayName)
            assertEquals(true, settings.onboardingComplete)
        }
    }
}
