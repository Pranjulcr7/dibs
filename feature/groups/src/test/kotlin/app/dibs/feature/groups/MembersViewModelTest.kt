// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.dibs.core.domain.ledger.InviteLink
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.ledger.SignedPayloads
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.settings.UserSettings
import app.dibs.core.domain.sync.BundleSync
import app.dibs.core.domain.sync.SyncStatus
import app.dibs.feature.groups.detail.GroupDetailViewModel
import app.dibs.feature.groups.members.InviteResult
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
    private lateinit var groupKeys: FakeGroupKeyStore
    private lateinit var syncMeta: FakeSyncMetaStore
    private lateinit var bundleSync: FakeBundleSync
    private val identity = FakeIdentitySource()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ledger = FakeLedger()
        settings = FakeSettingsStore(
            UserSettings(displayName = "Priya", selfMembers = mapOf(groupId to self)),
        )
        groupKeys = FakeGroupKeyStore()
        syncMeta = FakeSyncMetaStore()
        bundleSync = FakeBundleSync(ledger, syncMeta)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A group whose creator ([self]) has claimed their member, as create-group does. */
    private suspend fun seedGroup() {
        seedUnclaimedGroup()
        ledger.append(groupId, SignedPayloads.claimMember(groupId, self, identity.keyPair()))
    }

    /** A pre-identity group: members are names only, nobody has claimed (e.g. a Splitwise import). */
    private suspend fun seedUnclaimedGroup() {
        ledger.append(groupId, OpPayload.CreateGroup("Trip", defaultCurrency = CurrencyCode("USD")))
        ledger.append(groupId, OpPayload.AddMember(self, "Priya"))
    }

    private fun membersViewModel() = MembersViewModel(
        SavedStateHandle(mapOf("groupId" to groupId.value)),
        ledger,
        settings,
        groupKeys,
        identity,
    ).apply { wallClock = { NOW } }

    private fun detailViewModel() = GroupDetailViewModel(
        SavedStateHandle(mapOf("groupId" to groupId.value)),
        ledger,
        settings,
        bundleSync,
        syncMeta,
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
            // Claimed members always carry their fingerprint digits (SPEC 5.9);
            // unclaimed name records never do.
            assertEquals(4, rows.first { it.name == "Priya" }.fingerprint?.length)
            assertEquals(null, rows.first { it.name == "Zoe" }.fingerprint)
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
        val viewModel = detailViewModel()

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

    // -- FR-Y1 / SPEC 5.9: invites ------------------------------------------------

    @Test
    fun `given a claimed creator when inviting then a signed single-use invite is appended and the link carries the group key`() = runTest(dispatcher) {
        seedGroup()

        val result = membersViewModel().createInvite() as InviteResult.Ready

        val state = ledger.state(groupId)
        val invite = state.invites.values.single()
        assertEquals(result.link.inviteId, invite.id)
        assertEquals(1, invite.maxUses)
        assertEquals(NOW + SEVEN_DAYS_MILLIS, invite.expiresAtMillis)
        assertEquals(NOW + SEVEN_DAYS_MILLIS, result.expiresAtMillis)
        // The fold accepted it: a forged or unauthorised invite would quarantine instead.
        assertTrue(state.quarantined.isEmpty())
        assertTrue(result.link.groupKey.contentEquals(groupKeys.keyFor(groupId)))
        assertEquals(groupId, result.link.groupId)
        // And the link survives its own strict parser (edge 76).
        assertEquals(result.link, InviteLink.parse(result.link.encode()))
    }

    @Test
    fun `given a group nobody has claimed when inviting then our own member is claimed first so the invite validates`() = runTest(dispatcher) {
        seedUnclaimedGroup()

        val result = membersViewModel().createInvite()

        assertTrue(result is InviteResult.Ready)
        val state = ledger.state(groupId)
        assertEquals(identity.keyPair().publicKey, state.members.getValue(self).identityKey)
        assertEquals(1, state.invites.size)
        assertTrue(state.quarantined.isEmpty())
    }

    @Test
    fun `given someone else has claimed and we have not when inviting then nothing is written and the result says to ask for an invite`() = runTest(dispatcher) {
        seedUnclaimedGroup()
        val other = MemberId("0b000000-0000-4000-8000-000000000002")
        ledger.append(groupId, OpPayload.AddMember(other, "Sam"))
        ledger.append(groupId, SignedPayloads.claimMember(groupId, other, FakeIdentitySource(masterByte = 7).keyPair()))
        val opsBefore = ledger.opCount(groupId)

        val result = membersViewModel().createInvite()

        assertEquals(InviteResult.SelfNotClaimed, result)
        assertEquals(opsBefore, ledger.opCount(groupId))
        assertEquals(null, groupKeys.keyFor(groupId))
    }

    @Test
    fun `given two invites for one group then both links carry the same group key and distinct invite ids`() = runTest(dispatcher) {
        seedGroup()
        val viewModel = membersViewModel()

        val first = viewModel.createInvite() as InviteResult.Ready
        val second = viewModel.createInvite() as InviteResult.Ready

        assertTrue(first.link.groupKey.contentEquals(second.link.groupKey))
        assertFalse(first.link.inviteId == second.link.inviteId)
        assertEquals(2, ledger.state(groupId).invites.size)
    }

    // -- FR-Y4: the sync chip and import outcomes -----------------------------------

    @Test
    fun `given a group never shared then the sync chip stays quiet, and after a share it tracks new changes`() = runTest(dispatcher) {
        seedGroup()
        val viewModel = detailViewModel()

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.syncStatus == null) state = awaitItem()
            assertEquals(SyncStatus.NeverShared, state.syncStatus)

            val bytes = viewModel.exportSyncBundle()
            assertTrue(bytes.contentEquals(FakeBundleSync.BUNDLE_BYTES))
            while (state.syncStatus !is SyncStatus.UpToDate) state = awaitItem()
            assertEquals(SyncStatus.UpToDate(FakeBundleSync.SHARED_AT), state.syncStatus)

            ledger.append(groupId, OpPayload.AddMember(MemberId("0b000000-0000-4000-8000-000000000002"), "Sam"))
            while (state.syncStatus !is SyncStatus.ChangesToShare) state = awaitItem()
            assertEquals(SyncStatus.ChangesToShare(1), state.syncStatus)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `given an imported bundle then its outcome is emitted exactly once, whatever it is`() = runTest(dispatcher) {
        seedGroup()
        val viewModel = detailViewModel()
        val rejected = BundleSync.ImportOutcome.RejectedUnknownSender(groupId)
        bundleSync.nextImportOutcome = rejected

        viewModel.syncEvents.test {
            viewModel.importSyncBundle(byteArrayOf(9, 9))
            assertEquals(rejected, awaitItem())
            expectNoEvents()

            bundleSync.nextImportOutcome = BundleSync.ImportOutcome.Imported(groupId, 3, 0, emptyMap())
            viewModel.importSyncBundle(byteArrayOf(8))
            assertEquals(BundleSync.ImportOutcome.Imported(groupId, 3, 0, emptyMap()), awaitItem())
            cancelAndConsumeRemainingEvents()
        }
        assertEquals(2, bundleSync.imported.size)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
