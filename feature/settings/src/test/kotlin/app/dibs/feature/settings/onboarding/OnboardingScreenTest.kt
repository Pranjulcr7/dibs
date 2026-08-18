// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.settings.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.dibs.core.ui.theme.DibsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OnboardingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var finishedWith: String? = "sentinel-not-called"
    private var finishCalled = false

    private fun setScreen() {
        finishedWith = "sentinel-not-called"
        finishCalled = false
        compose.setContent {
            DibsTheme {
                OnboardingScreen(onFinish = { name ->
                    finishedWith = name
                    finishCalled = true
                })
            }
        }
    }

    @Test
    fun `given the first page when rendered then the pitch and both skip and next are available`() {
        setScreen()
        compose.onNodeWithText("Split expenses with friends").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertIsDisplayed()
        compose.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun `given the user taps next twice then the name page appears and finishing passes the typed name`() {
        setScreen()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Your data stays on this phone").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithTag("onboarding-name").performTextInput("Priya")
        compose.onNodeWithText("Get started").performClick()

        assertTrue(finishCalled)
        assertEquals("Priya", finishedWith)
    }

    @Test
    fun `given the user skips then onboarding finishes with no name from any page`() {
        setScreen()
        compose.onNodeWithText("Skip").performClick()
        assertTrue(finishCalled)
        assertEquals(null, finishedWith)
    }

    @Test
    fun `given the name page when the name is empty then getting started still works as an implicit skip`() {
        setScreen()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Get started").performClick()

        assertTrue(finishCalled)
        assertEquals(null, finishedWith)
    }
}
