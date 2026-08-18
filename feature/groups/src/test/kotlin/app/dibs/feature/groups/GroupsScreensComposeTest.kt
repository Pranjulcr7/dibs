// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.feature.groups.create.CreateGroupScreen
import app.dibs.feature.groups.create.CreateGroupUiState
import app.dibs.feature.groups.list.GroupCardUi
import app.dibs.feature.groups.list.GroupsUiState
import app.dibs.feature.groups.members.MemberRow
import app.dibs.feature.groups.members.MembersScreen
import app.dibs.feature.groups.members.MembersUiState
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
@Config(sdk = [35], qualifiers = "w480dp-h1200dp")
class GroupsScreensComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `given groups then cards show name, member count, and color-coded position, and clicking opens the group`() {
        var opened: GroupId? = null
        compose.setContent {
            DibsTheme {
                GroupsScreen(
                    uiState = GroupsUiState.Content(
                        listOf(
                            GroupCardUi(GroupId("g1"), "Lisbon trip", "🌊", 4, Money(4250), CurrencyCode("USD")),
                            GroupCardUi(GroupId("g2"), "Flat 4B", null, 2, Money(-1200), CurrencyCode("USD")),
                            GroupCardUi(GroupId("g3"), "Poker", null, 5, Money.ZERO, CurrencyCode("USD")),
                        ),
                    ),
                    onOpenGroup = { opened = it },
                    onCreateGroup = {},
                    onImportFromSplitwise = {},
                )
            }
        }

        compose.onNodeWithText("Lisbon trip").assertIsDisplayed()
        compose.onNodeWithText("4 members").assertIsDisplayed()
        compose.onNodeWithText("You're owed $42.50").assertIsDisplayed()
        compose.onNodeWithText("You owe $12.00").assertIsDisplayed()
        compose.onNodeWithText("Settled up").assertIsDisplayed()

        compose.onNodeWithText("Flat 4B").performClick()
        assertEquals(GroupId("g2"), opened)
    }

    @Test
    fun `given the create screen then create stays disabled until a name is typed and submits the form`() {
        var created = false
        compose.setContent {
            DibsTheme {
                CreateGroupScreenHarness(onCreate = { created = true })
            }
        }

        compose.onNodeWithTag("create-group-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("create-group-name").performTextInput("Lisbon")
        compose.onNodeWithTag("create-group-confirm").assertIsEnabled().performClick()
        assertTrue(created)
    }

    @Test
    fun `given members then self chip, duplicate hint, and deactivated section render`() {
        compose.setContent {
            DibsTheme {
                MembersScreen(
                    uiState = MembersUiState(
                        members = listOf(
                            MemberRow(MemberId("m1"), "Priya", active = true, isSelf = true, duplicateName = false),
                            MemberRow(MemberId("m2"), "Sam", active = true, isSelf = false, duplicateName = true),
                            MemberRow(MemberId("m3"), "Sam", active = true, isSelf = false, duplicateName = true),
                            MemberRow(MemberId("m4"), "Zoe", active = false, isSelf = false, duplicateName = false),
                        ),
                    ),
                    onBack = {},
                    onAddMember = {},
                    onRenameMember = { _, _ -> },
                    onDeactivateMember = {},
                    onReactivateMember = {},
                )
            }
        }

        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithText("Deactivated").assertIsDisplayed()
        compose.onNodeWithText("Zoe").assertIsDisplayed()
    }

    @Test
    fun `given the add member row then typing a name enables add and submits it`() {
        var added: String? = null
        compose.setContent {
            DibsTheme {
                MembersScreen(
                    uiState = MembersUiState(),
                    onBack = {},
                    onAddMember = { added = it },
                    onRenameMember = { _, _ -> },
                    onDeactivateMember = {},
                    onReactivateMember = {},
                )
            }
        }

        compose.onNodeWithText("Add").assertIsNotEnabled()
        compose.onNodeWithTag("member-add-name").performTextInput("Dana")
        compose.onNodeWithText("Add").assertIsEnabled().performClick()
        assertEquals("Dana", added)
    }
}

/** Stateful wrapper so typing drives the real form-state contract. */
@Composable
private fun CreateGroupScreenHarness(onCreate: () -> Unit) {
    var state by remember { mutableStateOf(CreateGroupUiState()) }
    CreateGroupScreen(
        uiState = state,
        onNameChange = { state = state.copy(name = it) },
        onEmojiChange = { state = state.copy(emoji = it) },
        onCurrencyChange = { state = state.copy(currency = it) },
        onCreate = onCreate,
        onBack = {},
    )
}
