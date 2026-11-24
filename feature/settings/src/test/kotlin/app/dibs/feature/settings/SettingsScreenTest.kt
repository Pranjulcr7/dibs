// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.dibs.core.domain.settings.ThemeMode
import app.dibs.core.domain.settings.UserSettings
import app.dibs.core.ui.theme.DibsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** F26 / F28 on screen: settings rows act as one control each, and About says what a bug report needs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w480dp-h2400dp")
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var theme: ThemeMode? = null
    private var appLock: Boolean? = null
    private var blockScreenshots: Boolean? = null
    private var dynamicColor: Boolean? = null

    private fun show(settings: UserSettings = UserSettings(displayName = "Priya"), version: String? = "1.2.3") {
        compose.setContent {
            DibsTheme {
                SettingsScreen(
                    settings = settings,
                    onThemeMode = { theme = it },
                    onDynamicColor = { dynamicColor = it },
                    onDisplayName = {},
                    onDefaultCurrency = {},
                    onAppLock = { appLock = it },
                    onBlockScreenshots = { blockScreenshots = it },
                    versionName = version,
                )
            }
        }
    }

    @Test
    fun `FR-T6 - given About then the version, the licence, and the collects-nothing statement are shown`() {
        show()

        compose.onNodeWithTag("about-version").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Version 1.2.3").assertIsDisplayed()
        compose.onNodeWithText("Apache-2.0 · source on GitHub").assertIsDisplayed()
        compose.onNodeWithText("Dibs collects nothing.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `NFR-10 - given a switch row then tapping its label toggles it and it reports its state as one control`() {
        show(UserSettings(blockScreenshots = false, appLockEnabled = true))

        // The tag is on the row, which carries the label and the on/off state together.
        compose.onNodeWithTag("block-screenshots").performScrollTo().assertIsOff()
        compose.onNodeWithTag("app-lock").assertIsOn()
        compose.onNodeWithText("Block screenshots").performClick()
        compose.onNodeWithText("App lock").performClick()

        assertEquals(true, blockScreenshots)
        assertEquals(false, appLock)
    }

    @Test
    fun `FR-T1 - given the theme choices then picking one reports it`() {
        show()

        compose.onNodeWithTag("theme-dark").performClick()

        assertEquals(ThemeMode.DARK, theme)
    }
}
