// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import app.cash.turbine.test
import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.crypto.IdentitySource
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.settings.UserSettings
import app.dibs.feature.groups.create.CreateGroupViewModel
import app.dibs.feature.groups.list.GroupsUiState
import app.dibs.feature.groups.list.GroupsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GroupsViewModelsTest {

    private val dispatcher = StandardTestDispatcher()
    private val testKeyPair = Identity.keyPairFromMaster(ByteArray(32) { 42 })
    private val identity = object : IdentitySource {
        override suspend fun keyPair() = testKeyPair
    }
    private lateinit var ledger: FakeLedger
    private lateinit var settings: FakeSettingsStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ledger = FakeLedger()
        settings = FakeSettingsStore(UserSettings(displayName = "Priya"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `given no groups then the list state is empty content`() = runTest(dispatcher) {
        val viewModel = GroupsViewModel(ledger, settings)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is GroupsUiState.Content) state = awaitItem()
            assertEquals(0, state.groups.size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `given a created group with expenses then the card shows name, member count, and my net position`() = runTest(dispatcher) {
        val create = CreateGroupViewModel(ledger, settings, identity)
        create.onNameChange("Lisbon trip")
        create.onEmojiChange("🌊")
        create.create(fallbackSelfName = "You")
        dispatcher.scheduler.advanceUntilIdle()
        val groupId = create.created.first()

        // Add a friend and an expense paid by me, split evenly.
        val self = settings.state.value.selfMembers.getValue(groupId)
        val bob = MemberId("0b000000-0000-4000-8000-000000000002")
        ledger.append(groupId, OpPayload.AddMember(bob, "Bob"))
        ledger.append(
            groupId,
            OpPayload.AddExpense(
                app.dibs.core.domain.model.ExpenseId("e1000000-0000-4000-8000-000000000000"),
                app.dibs.core.domain.ledger.ExpenseFields(
                    description = "dinner",
                    currency = CurrencyCode("USD"),
                    total = Money(1000),
                    paidBy = mapOf(self to Money(1000)),
                    owedBy = mapOf(self to Money(500), bob to Money(500)),
                    dateEpochDay = 20_000,
                ),
            ),
        )

        val viewModel = GroupsViewModel(ledger, settings)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is GroupsUiState.Content || state.groups.isEmpty()) state = awaitItem()
            val card = state.groups.single()
            assertEquals("Lisbon trip", card.name)
            assertEquals("🌊", card.emoji)
            assertEquals(2, card.memberCount)
            assertEquals(Money(500), card.myNet)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `given create when the name is filled then it appends group metadata plus a self member and records the mapping`() = runTest(dispatcher) {
        val create = CreateGroupViewModel(ledger, settings, identity)
        create.onNameChange("  Flat 4B  ")
        create.onCurrencyChange(CurrencyCode("EUR"))
        create.create(fallbackSelfName = "You")
        dispatcher.scheduler.advanceUntilIdle()
        val groupId = create.created.first()

        val state = ledger.state(groupId)
        assertEquals("Flat 4B", state.meta?.name)
        assertEquals(CurrencyCode("EUR"), state.meta?.defaultCurrency)
        val selfId = settings.state.value.selfMembers[groupId]
        assertNotNull("self member mapping must be recorded", selfId)
        assertEquals("Priya", state.members.getValue(selfId!!).name)
        // The create flow bootstrap-claims the self member (SPEC 5.9).
        assertEquals(testKeyPair.publicKey, state.members.getValue(selfId).identityKey)
        assertEquals(0, state.quarantined.size)
    }

    @Test
    fun `given a blank display name then the self member falls back to the provided default`() = runTest(dispatcher) {
        settings.state.value = UserSettings(displayName = "")
        val create = CreateGroupViewModel(ledger, settings, identity)
        create.onNameChange("Trip")
        create.create(fallbackSelfName = "You")
        dispatcher.scheduler.advanceUntilIdle()
        val groupId = create.created.first()

        val selfId = settings.state.value.selfMembers.getValue(groupId)
        assertEquals("You", ledger.state(groupId).members.getValue(selfId).name)
    }

    @Test
    fun `given a blank group name then create does nothing`() = runTest(dispatcher) {
        val create = CreateGroupViewModel(ledger, settings, identity)
        create.onNameChange("   ")
        create.create(fallbackSelfName = "You")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(ledger.observeGroupIds().first().isEmpty())
    }

    // -- FR-I5: the backup reminder ---------------------------------------------------

    private suspend fun nudgeFor(lastBackupDaysAgo: Long?, backedUpOpCount: Long): Boolean {
        val now = 1_700_000_000_000L
        settings.state.value = settings.state.value.copy(
            lastBackupAtMillis = lastBackupDaysAgo?.let { now - it * 24 * 60 * 60 * 1000 },
            backedUpOpCount = backedUpOpCount,
        )
        val viewModel = GroupsViewModel(ledger, settings).apply { wallClock = { now } }
        var nudge = false
        // Subscribed so the StateFlow is live; then read where it settled.
        viewModel.backupNudge.test {
            dispatcher.scheduler.advanceUntilIdle()
            nudge = viewModel.backupNudge.value
            cancelAndConsumeRemainingEvents()
        }
        return nudge
    }

    @Test
    fun `FR-I5 - given data and no backup ever then the reminder shows`() = runTest(dispatcher) {
        ledger.append(GroupId("11111111-0000-4000-8000-000000000000"), OpPayload.CreateGroup("Trip", defaultCurrency = CurrencyCode("USD")))

        assertTrue(nudgeFor(lastBackupDaysAgo = null, backedUpOpCount = 0))
    }

    @Test
    fun `FR-I5 - given an empty app then there is nothing to remind about`() = runTest(dispatcher) {
        assertFalse(nudgeFor(lastBackupDaysAgo = null, backedUpOpCount = 0))
    }

    @Test
    fun `FR-I5 - given a backup older than 30 days then the reminder shows only if the ledger changed since`() = runTest(dispatcher) {
        val group = GroupId("11111111-0000-4000-8000-000000000000")
        ledger.append(group, OpPayload.CreateGroup("Trip", defaultCurrency = CurrencyCode("USD")))
        val countAtBackup = ledger.totalOpCount()

        assertFalse("unchanged since the backup: nothing new to protect", nudgeFor(31, countAtBackup))

        ledger.append(group, OpPayload.AddMember(MemberId("0a000000-0000-4000-8000-000000000001"), "Alice"))
        assertTrue("changed, and the backup is stale", nudgeFor(31, countAtBackup))
        assertFalse("changed, but backed up recently", nudgeFor(29, countAtBackup))
    }
}
