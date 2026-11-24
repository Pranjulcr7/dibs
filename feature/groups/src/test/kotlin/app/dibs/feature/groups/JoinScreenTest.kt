// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.feature.groups.join.ClaimableMember
import app.dibs.feature.groups.join.JoinActions
import app.dibs.feature.groups.join.JoinProblem
import app.dibs.feature.groups.join.JoinScreen
import app.dibs.feature.groups.join.JoinUiState
import app.dibs.feature.groups.join.messageRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** F22 on screen: each join step shows what it should and hands the right choice back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w480dp-h1600dp")
class JoinScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val sam = MemberId("0b000000-0000-4000-8000-000000000002")
    private val choose = JoinUiState.ChooseMember(
        groupName = "Goa trip",
        inviterName = "Priya",
        claimable = listOf(ClaimableMember(sam, "Sam")),
        suggestedName = "Sammy",
        approvalRequired = true,
    )

    private fun show(state: JoinUiState, actions: JoinActions = JoinActions()) {
        compose.setContent { DibsTheme { JoinScreen(state, actions) } }
    }

    @Test
    fun `given the first step then opening a file, scanning, and pasting a link are all offered`() {
        var link: String? = null
        var picked = false
        var scanned = false
        show(
            JoinUiState.EnterLink,
            JoinActions(onOpenLink = { link = it }, onPickFile = { picked = true }, onScanCode = { scanned = true }),
        )

        compose.onNodeWithTag("join-open-file").performClick()
        compose.onNodeWithTag("join-scan").performClick()
        compose.onNodeWithTag("join-link-field").performTextInput("dibs://join?g=1")
        compose.onNodeWithTag("join-continue").performClick()

        assertTrue(picked)
        assertTrue(scanned)
        assertEquals("dibs://join?g=1", link)
    }

    @Test
    fun `given members to claim then picking one and joining claims exactly that member`() {
        var claimed: MemberId? = null
        var joinedAs: String? = null
        show(choose, JoinActions(onClaim = { claimed = it }, onJoinAsNew = { joinedAs = it }))

        compose.onNodeWithText("Join Goa trip").assertIsDisplayed()
        compose.onNodeWithText("Invited by Priya").assertIsDisplayed()
        compose.onNodeWithText("This group approves new members.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("join-member-${sam.value}").performClick()
        compose.onNodeWithTag("join-confirm").performScrollTo().performClick()

        assertEquals(sam, claimed)
        assertEquals(null, joinedAs)
    }

    @Test
    fun `given not on the list then the name from onboarding is offered, a blank name blocks joining, and a name joins as new`() {
        var joinedAs: String? = null
        show(choose, JoinActions(onJoinAsNew = { joinedAs = it }))

        // "I'm not on the list" is the default selection: nobody is claimed by accident.
        compose.onNodeWithTag("join-name-field").performTextClearance()
        compose.onNodeWithTag("join-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("join-name-field").performTextInput("Samira")
        compose.onNodeWithTag("join-confirm").assertIsEnabled().performScrollTo().performClick()

        assertEquals("Samira", joinedAs)
    }

    @Test
    fun `given a fresh join then telling the group comes first and opening the group is still possible`() {
        var shared = false
        var opened: GroupId? = null
        show(
            JoinUiState.Joined(group, "Goa trip", pendingApproval = true, justJoined = true),
            JoinActions(onShareJoin = { shared = true }, onOpenGroup = { opened = it }),
        )

        compose.onNodeWithText("You're in Goa trip").assertIsDisplayed()
        compose.onNodeWithText("Someone in the group still needs to approve you.").assertIsDisplayed()
        compose.onNodeWithTag("join-share").performClick()
        compose.onNodeWithTag("join-open-group").performClick()

        assertTrue(shared)
        assertEquals(group, opened)
    }

    @Test
    fun `given a bare link then the screen asks for the group's updates, and says so differently when the file was too old`() {
        show(JoinUiState.NeedsUpdates(stale = true))

        compose.onNodeWithText("The file you opened was made before this invite.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("join-open-updates").assertIsDisplayed()
    }

    @Test
    fun `given updates with nothing new, or with changes from a newer app, then the screen says exactly that`() {
        show(JoinUiState.UpdatesImported(group, "Goa trip", opsAdded = 0, mayBeMissing = false, skippedNewer = true))

        compose.onNodeWithText("You already had everything in this file.").assertIsDisplayed()
        compose.onNodeWithText("newer version of Dibs", substring = true).assertIsDisplayed()
    }

    @Test
    fun `given every join problem then each has its own sentence and a way forward`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val messages = JoinProblem.entries.map { context.getString(it.messageRes()) }
        assertTrue(messages.all { it.isNotBlank() })
        assertEquals("no two problems may share a message", messages.size, messages.toSet().size)

        var startedOver = false
        show(JoinUiState.Problem(JoinProblem.INVITE_EXPIRED), JoinActions(onStartOver = { startedOver = true }))
        compose.onNodeWithText("This invite has expired. Ask for a new one.").assertIsDisplayed()
        compose.onNodeWithTag("join-start-over").performClick()
        assertTrue(startedOver)
    }
}
