// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.feature.groups.list.GroupsUiState
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
class GroupsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `given no groups when rendered then the empty state offers create and import side by side`() {
        var createClicked = false
        var importClicked = false
        compose.setContent {
            DibsTheme {
                GroupsScreen(
                    uiState = GroupsUiState.Content(emptyList()),
                    onOpenGroup = {},
                    onCreateGroup = { createClicked = true },
                    onImportFromSplitwise = { importClicked = true },
                )
            }
        }

        compose.onNodeWithText("No groups yet").assertIsDisplayed()
        compose.onNodeWithText("Create a group").assertIsDisplayed().performClick()
        compose.onNodeWithText("Import from Splitwise").assertIsDisplayed().performClick()
        assertTrue(createClicked)
        assertTrue(importClicked)
    }

    @Test
    fun `given no activity when the activity tab renders then its empty state explains what will appear`() {
        compose.setContent {
            DibsTheme { ActivityScreen() }
        }
        compose.onNodeWithText("No activity yet").assertIsDisplayed()
    }
}
