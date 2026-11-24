// SPDX-License-Identifier: Apache-2.0
package app.dibs

import app.cash.turbine.test
import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.crypto.IdentityKeyPair
import app.dibs.core.domain.crypto.IdentitySource
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.GroupLedger
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.Op
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.settings.SettingsStore
import app.dibs.core.domain.settings.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** FR-G5 and the home FAB's targets (NFR-2). */
class QuickAddViewModelTest {

    /** The real domain fold behind an in-memory [Ledger]. */
    private class InMemoryLedger : Ledger {
        private val ledgers = LinkedHashMap<GroupId, GroupLedger>()
        private val ids = MutableStateFlow<Set<GroupId>>(emptySet())
        private val states = LinkedHashMap<GroupId, MutableStateFlow<GroupState>>()
        private var clock = 1_000L

        private fun ledgerFor(id: GroupId) = ledgers.getOrPut(id) { GroupLedger(id, DeviceId("aa")) }

        private fun flowFor(id: GroupId) = states.getOrPut(id) { MutableStateFlow(ledgerFor(id).state()) }

        override fun observeGroupIds(): Flow<Set<GroupId>> = ids

        override fun observeState(groupId: GroupId): Flow<GroupState> = flowFor(groupId)

        override suspend fun state(groupId: GroupId): GroupState = ledgerFor(groupId).state()

        override suspend fun append(groupId: GroupId, payload: OpPayload): Op {
            val op = ledgerFor(groupId).append(payload, ++clock)
            flowFor(groupId).value = ledgerFor(groupId).state()
            ids.value = ids.value + groupId
            return op
        }

        override suspend fun ingest(groupId: GroupId, ops: Collection<Op>) {
            ledgerFor(groupId).receive(ops)
        }

        override suspend fun allOps(groupId: GroupId): List<Op> = ledgerFor(groupId).log.all.toList()

        override suspend fun pendingCount(groupId: GroupId): Int = ledgerFor(groupId).log.pending.size

        override suspend fun totalOpCount(): Long = ledgers.values.sumOf { it.log.all.size.toLong() }
    }

    private class InMemorySettings(initial: UserSettings) : SettingsStore {
        val state = MutableStateFlow(initial)
        override val settings: Flow<UserSettings> = state

        override suspend fun update(transform: (UserSettings) -> UserSettings) {
            state.value = transform(state.value)
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val ledger = InMemoryLedger()
    private val settings = InMemorySettings(UserSettings(displayName = "Priya", defaultCurrency = CurrencyCode("EUR")))
    private val keyPair = Identity.keyPairFromMaster(ByteArray(32) { 5 })
    private val identity = object : IdentitySource {
        override suspend fun keyPair(): IdentityKeyPair = keyPair
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = QuickAddViewModel(ledger, settings, identity)

    @Test
    fun `FR-G5 - given just this once then a hidden group is created with me in it, claimed, in my default currency`() = runTest(dispatcher) {
        val groupId = viewModel().createAdHocGroup(groupName = "One-off", fallbackSelfName = "You")

        val state = ledger.state(groupId)
        assertEquals("One-off", state.meta?.name)
        assertTrue("ad-hoc groups are marked so the list can hide them", state.meta?.adHoc == true)
        assertEquals(CurrencyCode("EUR"), state.meta?.defaultCurrency)
        val me = state.members.values.single()
        assertEquals("Priya", me.name)
        assertEquals(keyPair.publicKey, me.identityKey)
        assertEquals(me.id, settings.state.value.selfMembers[groupId])
        assertTrue(state.quarantined.isEmpty())
    }

    @Test
    fun `given regular, ad-hoc, and archived groups then the add-expense picker offers only the regular ones, by name`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val zebra = GroupId("11111111-0000-4000-8000-000000000001")
        val apple = GroupId("11111111-0000-4000-8000-000000000002")
        val old = GroupId("11111111-0000-4000-8000-000000000003")
        ledger.append(zebra, OpPayload.CreateGroup("Zebra", defaultCurrency = CurrencyCode("USD")))
        ledger.append(apple, OpPayload.CreateGroup("apple", emoji = "🍎", defaultCurrency = CurrencyCode("USD")))
        ledger.append(old, OpPayload.CreateGroup("Old trip", defaultCurrency = CurrencyCode("USD")))
        ledger.append(old, OpPayload.SetArchived(true))
        viewModel.createAdHocGroup("One-off", "You")

        viewModel.targets.test {
            var targets = awaitItem()
            while (targets.size < 2) targets = awaitItem()
            dispatcher.scheduler.advanceUntilIdle()
            targets = expectMostRecentItemOr(targets)
            assertEquals(listOf("🍎 apple", "Zebra"), targets.map { it.label })
            cancelAndConsumeRemainingEvents()
        }
    }

    private fun <T> app.cash.turbine.TurbineTestContext<T>.expectMostRecentItemOr(fallback: T): T = try {
        expectMostRecentItem()
    } catch (_: AssertionError) {
        fallback
    }
}
