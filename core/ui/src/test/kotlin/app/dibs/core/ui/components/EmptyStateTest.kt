// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.dibs.core.ui.theme.DibsTheme
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
class EmptyStateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `given an empty state when rendered then title, body, and actions are visible and clickable`() {
        var clicked = false
        compose.setContent {
            DibsTheme {
                EmptyState(
                    title = "No groups yet",
                    body = "Create a group to start splitting expenses.",
                ) {
                    Button(onClick = { clicked = true }) { Text("Create a group") }
                }
            }
        }

        compose.onNodeWithText("No groups yet").assertIsDisplayed()
        compose.onNodeWithText("Create a group to start splitting expenses.").assertIsDisplayed()
        compose.onNodeWithText("Create a group").performClick()
        assertTrue(clicked)
    }
}
