// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.settings.UserSettings
import app.dibs.feature.groups.detail.GroupDetailViewModel
import app.dibs.feature.groups.members.MembersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MembersViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val groupId = GroupId("11111111-0000-4000-8000-000000000000")
    private val self = MemberId("0a000000-0000-4000-8000-000000000001")
    private lateinit var ledger: FakeLedger
    private lateinit var settings: FakeSettingsStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ledger = FakeLedger()
        settings = FakeSettingsStore(
            UserSettings(displayName = "Priya", selfMembers = mapOf(groupId to self)),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun seedGroup() {
        ledger.append(groupId, OpPayload.CreateGroup("Trip", defaultCurrency = CurrencyCode("USD")))
        ledger.append(groupId, OpPayload.AddMember(self, "Priya"))
    }

    private fun membersViewModel() = MembersViewModel(
        SavedStateHandle(mapOf("groupId" to groupId.value)),
        ledger,
        settings,
    )

    @Test
    fun `given members then rows mark self, flag duplicate names, and sort active before inactive`() = runTest(dispatcher) {
        seedGroup()
        ledger.append(groupId, OpPayload.AddMember(MemberId("0b000000-0000-4000-8000-000000000002"), "Sam"))
        ledger.append(groupId, OpPayload.AddMember(MemberId("0c000000-0000-4000-8000-000000000003"), "Sam"))
        ledger.append(groupId, OpPayload.AddMember(MemberId("0d000000-0000-4000-8000-000000000004"), "Zoe"))
        ledger.append(groupId, OpPayload.DeactivateMember(MemberId("0d000000-0000-4000-8000-000000000004")))

        membersViewModel().uiState.test {
            var state = awaitItem()
            while (state.members.size < 4) state = awaitItem()

            val rows = state.members
            assertTrue(rows.first { it.name == "Priya" }.isSelf)
            assertTrue(rows.filter { it.name == "Sam" }.all { it.duplicateName })
            assertFalse(rows.first { it.name == "Priya" }.duplicateName)
            val zoe = rows.first { it.name == "Zoe" }
            assertFalse(zoe.active)
            assertEquals("inactive members sort last", rows.last().name, "Zoe")
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `given member actions then add, rename, deactivate, and reactivate reach the ledger`() = runTest(dispatcher) {
        seedGroup()
        val viewModel = membersViewModel()

        viewModel.addMember("  Dana  ")
        dispatcher.scheduler.advanceUntilIdle()
        val dana = ledger.state(groupId).members.values.first { it.name == "Dana" }

        viewModel.renameMember(dana.id, "Dana K")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Dana K", ledger.state(groupId).members.getValue(dana.id).name)

        viewModel.deactivateMember(dana.id)
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(ledger.state(groupId).members.getValue(dana.id).active)

        viewModel.reactivateMember(dana.id)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(ledger.state(groupId).members.getValue(dana.id).active)
    }

    @Test
    fun `given a blank member name then nothing is appended`() = runTest(dispatcher) {
        seedGroup()
        val viewModel = membersViewModel()
        viewModel.addMember("   ")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, ledger.state(groupId).members.size)
    }

    @Test
    fun `given the detail view model then state, my net, and metadata edits flow through`() = runTest(dispatcher) {
        seedGroup()
        val viewModel = GroupDetailViewModel(
            SavedStateHandle(mapOf("groupId" to groupId.value)),
            ledger,
            settings,
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.state == null) state = awaitItem()
            assertEquals("Trip", state.state?.meta?.name)
            assertEquals(self, state.selfMember)

            viewModel.editGroup(name = "Trip 2026", emoji = "🎒", defaultCurrency = null)
            state = awaitItem()
            while (state.state?.meta?.name != "Trip 2026") state = awaitItem()
            assertEquals("🎒", state.state?.meta?.emoji)
            cancelAndConsumeRemainingEvents()
        }
    }
}
