// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.expense.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.ui.theme.DibsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w480dp-h1600dp")
class ExpenseEditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")

    @Composable
    private fun Harness() {
        var draft by remember {
            mutableStateOf(
                EditorDraft(
                    dateEpochDay = 20_000,
                    payer = alice.value,
                    participants = setOf(alice.value, bob.value),
                ),
            )
        }
        ExpenseEditorScreen(
            uiState = EditorUiState(
                draft = draft,
                members = listOf(
                    EditorMember(alice, "Alice", isSelf = true),
                    EditorMember(bob, "Bob", isSelf = false),
                ),
                currency = CurrencyCode("USD"),
                loaded = true,
            ),
            onDraftChange = { transform -> draft = transform(draft) },
            onSave = {},
            onDelete = {},
            onBack = {},
        )
    }

    @Test
    fun `given an empty editor then save is disabled and the bar asks for an amount`() {
        compose.setContent { DibsTheme { Harness() } }
        compose.onNodeWithTag("save-expense").assertIsNotEnabled()
        compose.onNodeWithText("Enter an amount").assertIsDisplayed()
    }

    @Test
    fun `given an equal ten-dollar split of three cents remainder then the bar goes ready and names the absorber`() {
        compose.setContent { DibsTheme { Harness() } }
        compose.onNodeWithTag("amount").performTextInput("10.01")
        compose.onNodeWithText("All assigned").assertIsDisplayed()
        compose.onNodeWithText("Alice +\$0.01 to make it add up").assertIsDisplayed()
    }

    @Test
    fun `given exact mode with a partial assignment then the bar shows the domain remaining`() {
        compose.setContent { DibsTheme { Harness() } }
        compose.onNodeWithTag("amount").performTextInput("10.00")
        compose.onNodeWithTag("mode-exact").performClick()
        compose.onNodeWithTag("exact-${alice.value}").performScrollTo().performTextInput("3.00")
        compose.onNodeWithText("\$7.00 left to assign").assertIsDisplayed()
    }

    @Test
    fun `given itemized mode then the amount field is replaced by the computed total`() {
        compose.setContent { DibsTheme { Harness() } }
        compose.onNodeWithTag("mode-itemized").performClick()
        compose.onNodeWithTag("amount-computed").assertIsDisplayed()
        compose.onNodeWithTag("add-item").performScrollTo().performClick()
        compose.onNodeWithTag("item-price-0").performScrollTo().performTextInput("5.00")
        compose.onNodeWithText("Assign every item to someone").assertIsDisplayed()
    }
}
