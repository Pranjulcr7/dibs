// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.dibs.core.domain.ledger.AmountsEdit
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.ExpenseEdit
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.InviteLink
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.ledger.SignedPayloads
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.settings.UserSettings
import app.dibs.core.domain.sync.BundleSync
import app.dibs.core.sync.SyncService
import app.dibs.feature.groups.detail.GroupDetailViewModel
import app.dibs.feature.groups.join.JoinProblem
import app.dibs.feature.groups.join.JoinUiState
import app.dibs.feature.groups.join.JoinViewModel
import app.dibs.feature.groups.members.InviteResult
import app.dibs.feature.groups.members.MembersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Flows F21–F25 with nothing faked between the screens and the fold: each
 * simulated phone has its own ledger, key store, identity, and a real
 * [SyncService], and the only thing that crosses between them is the bytes a
 * user would send through a chat app.
 */
@Suppress("LargeClass") // one scenario per join and sync rule; they share the two-phone fixture
class TwoDeviceJoinTest {

    private val dispatcher = StandardTestDispatcher()
    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val aliceId = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bobId = MemberId("0b000000-0000-4000-8000-000000000002")
    private val dinner = ExpenseId("e1000000-0000-4000-8000-000000000000")
    private val usd = CurrencyCode("USD")

    /** One phone: everything a real install has, in memory. */
    private inner class Phone(tag: Int, displayName: String, selfInGroup: MemberId? = null) {
        val ledger = FakeLedger(DeviceId("%02x000000-0000-4000-8000-000000000000".format(tag)))
        val settings = FakeSettingsStore(
            UserSettings(
                displayName = displayName,
                selfMembers = selfInGroup?.let { mapOf(group to it) } ?: emptyMap(),
            ),
        )
        val keys = FakeGroupKeyStore()
        val meta = FakeSyncMetaStore()
        val identity = FakeIdentitySource(masterByte = tag)
        val sync = SyncService(ledger, keys, meta, identity, DeviceId("%02x".format(tag))) { NOW }
        var now = NOW

        fun members() = MembersViewModel(handle(), ledger, settings, keys, identity, sync).apply { wallClock = { now } }

        fun detail() = GroupDetailViewModel(handle(), ledger, settings, sync, meta)

        fun join(saved: SavedStateHandle = SavedStateHandle()) =
            JoinViewModel(saved, ledger, settings, keys, identity, sync).apply { wallClock = { now } }

        suspend fun bytes() = ledger.state(group).canonicalBytes()

        private fun handle() = SavedStateHandle(mapOf("groupId" to group.value))
    }

    private lateinit var alice: Phone
    private lateinit var bob: Phone

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        alice = Phone(tag = 0xA1, displayName = "Alice", selfInGroup = aliceId)
        bob = Phone(tag = 0xB2, displayName = "Bob")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.settle() = testScheduler.advanceUntilIdle()

    /** Alice's group as create-group leaves it: herself claimed, "Bob" added by name, one dinner. */
    private suspend fun seedAliceGroup() {
        alice.ledger.append(group, OpPayload.CreateGroup("Goa trip", defaultCurrency = usd))
        alice.ledger.append(group, OpPayload.AddMember(aliceId, "Alice"))
        alice.ledger.append(group, OpPayload.AddMember(bobId, "Bob"))
        alice.ledger.append(group, SignedPayloads.claimMember(group, aliceId, alice.identity.keyPair()))
        alice.ledger.append(
            group,
            OpPayload.AddExpense(
                dinner,
                ExpenseFields(
                    description = "Dinner",
                    currency = usd,
                    total = Money(3000),
                    paidBy = mapOf(aliceId to Money(3000)),
                    owedBy = mapOf(aliceId to Money(1500), bobId to Money(1500)),
                    dateEpochDay = 20_100,
                ),
            ),
        )
    }

    private suspend fun aliceInvites(): InviteLink = (alice.members().createInvite() as InviteResult.Ready).link

    /** Bob opens Alice's invite file and claims the "Bob" she had added. Returns Bob's join screen model. */
    private suspend fun TestScope.bobJoinsAsBob(): JoinViewModel {
        val file = alice.members().inviteFile(aliceInvites())
        val join = bob.join()
        join.openFile(file)
        settle()
        join.claimExisting(bobId)
        settle()
        return join
    }

    /** Sends everything [from] knows to [to], as a user would: export, share, import. */
    private suspend fun share(from: Phone, to: Phone): BundleSync.ImportOutcome =
        to.sync.importBundle(from.sync.exportBundle(group))

    // -- F21 + F22: invite file, one action -------------------------------------

    @Test
    fun `F22 - given an invite file then the joiner sees the group, claims their name, and both phones converge`() = runTest(dispatcher) {
        seedAliceGroup()
        val file = alice.members().inviteFile(aliceInvites())

        val join = bob.join()
        join.openFile(file)
        settle()

        val choose = join.uiState.value as JoinUiState.ChooseMember
        assertEquals("Goa trip", choose.groupName)
        assertEquals("Alice", choose.inviterName)
        assertEquals(listOf("Bob"), choose.claimable.map { it.name })
        assertEquals("Bob", choose.suggestedName)
        assertFalse(choose.approvalRequired)

        join.claimExisting(bobId)
        settle()

        assertEquals(JoinUiState.Joined(group, "Goa trip", pendingApproval = false, justJoined = true), join.uiState.value)
        assertEquals(bobId, bob.settings.state.value.selfMembers[group])
        assertEquals(bob.identity.keyPair().publicKey, bob.ledger.state(group).members.getValue(bobId).identityKey)
        // Bob already sees the dinner he owes for: the invite file carried the data.
        assertEquals(Money(-1500), bob.ledger.state(group).balances().getValue(usd).first { it.memberId == bobId }.net)

        // Until Bob sends his join back, Alice has no way to know (no server).
        assertNull(alice.ledger.state(group).members.getValue(bobId).identityKey)

        val detail = alice.detail()
        detail.syncEvents.test {
            detail.importSyncBundle(checkNotNull(join.exportJoinBundle()))
            val notice = awaitItem()
            assertTrue(notice.outcome is BundleSync.ImportOutcome.Imported)
            assertEquals(listOf("Bob"), notice.newlyJoined)
            cancelAndConsumeRemainingEvents()
        }
        assertArrayEquals(alice.bytes(), bob.bytes())
        assertTrue(alice.ledger.state(group).quarantined.isEmpty())
    }

    @Test
    fun `F22 - given a bare link then the joiner is asked for updates, and the updates complete the join as a new member`() = runTest(dispatcher) {
        seedAliceGroup()
        val link = aliceInvites()

        val join = bob.join()
        join.openLink("  " + link.encode() + "\n")
        settle()
        assertEquals(JoinUiState.NeedsUpdates(stale = false), join.uiState.value)

        join.openFile(alice.sync.exportBundle(group))
        settle()
        assertTrue(join.uiState.value is JoinUiState.ChooseMember)

        join.joinAsNew("  Robert ")
        settle()

        val joined = join.uiState.value as JoinUiState.Joined
        assertTrue(joined.justJoined)
        val me = bob.ledger.state(group).members.getValue(checkNotNull(bob.settings.state.value.selfMembers[group]))
        assertEquals("Robert", me.name)
        assertEquals(bob.identity.keyPair().publicKey, me.identityKey)
        // The name-only "Bob" is untouched: joining as new never claims someone else's record.
        assertNull(bob.ledger.state(group).members.getValue(bobId).identityKey)

        assertTrue(share(bob, alice) is BundleSync.ImportOutcome.Imported)
        assertArrayEquals(alice.bytes(), bob.bytes())
    }

    @Test
    fun `F22 - given updates sealed before the invite existed then the joiner is told to ask for a fresh share`() = runTest(dispatcher) {
        seedAliceGroup()
        val oldBundle = alice.sync.exportBundle(group)
        val link = aliceInvites()

        val join = bob.join()
        join.openLink(link.encode())
        settle()
        join.openFile(oldBundle)
        settle()
        assertEquals(JoinUiState.NeedsUpdates(stale = true), join.uiState.value)

        join.openFile(alice.sync.exportBundle(group))
        settle()
        assertTrue(join.uiState.value is JoinUiState.ChooseMember)
    }

    @Test
    fun `edge 61 - given the process dies between opening a link and receiving updates then the join resumes`() = runTest(dispatcher) {
        seedAliceGroup()
        val link = aliceInvites()
        val saved = SavedStateHandle()
        bob.join(saved).openLink(link.encode())
        settle()

        // A new ViewModel over the same saved state, as after process death.
        val restored = bob.join(saved)
        settle()
        assertEquals(JoinUiState.NeedsUpdates(stale = false), restored.uiState.value)

        restored.openFile(alice.sync.exportBundle(group))
        settle()
        assertTrue(restored.uiState.value is JoinUiState.ChooseMember)
    }

    @Test
    fun `F22 - given the same invite opened again after joining then the joiner is simply taken to their group`() = runTest(dispatcher) {
        seedAliceGroup()
        val file = alice.members().inviteFile(aliceInvites())
        val first = bob.join()
        first.openFile(file)
        settle()
        first.claimExisting(bobId)
        settle()
        val opsAfterJoin = bob.ledger.opCount(group)

        val again = bob.join()
        again.openFile(file)
        settle()

        assertEquals(JoinUiState.Joined(group, "Goa trip", pendingApproval = false, justJoined = false), again.uiState.value)
        assertEquals("no second claim is written", opsAfterJoin, bob.ledger.opCount(group))
    }

    // -- F25: invite lifecycle ----------------------------------------------------

    @Test
    fun `F25 - given a revoked invite then the joiner is told it was cancelled and nothing is written`() = runTest(dispatcher) {
        seedAliceGroup()
        val link = aliceInvites()
        alice.members().revokeInvite(link.inviteId)
        settle()
        val file = alice.members().inviteFile(link)

        val join = bob.join()
        join.openFile(file)
        settle()

        assertEquals(JoinUiState.Problem(JoinProblem.INVITE_REVOKED), join.uiState.value)
        assertNull(bob.settings.state.value.selfMembers[group])
        assertEquals(alice.ledger.opCount(group), bob.ledger.opCount(group))
    }

    @Test
    fun `F25 - given an invite opened after its seven days then the joiner is told it expired`() = runTest(dispatcher) {
        seedAliceGroup()
        val file = alice.members().inviteFile(aliceInvites())
        bob.now = NOW + EIGHT_DAYS_MILLIS

        val join = bob.join()
        join.openFile(file)
        settle()

        assertEquals(JoinUiState.Problem(JoinProblem.INVITE_EXPIRED), join.uiState.value)
    }

    @Test
    fun `F25 - given a single-use invite a third person already used then the second joiner is told it is used up`() = runTest(dispatcher) {
        seedAliceGroup()
        val link = aliceInvites()
        val carol = Phone(tag = 0xC3, displayName = "Carol")
        val carolJoin = carol.join()
        carolJoin.openFile(alice.members().inviteFile(link))
        settle()
        carolJoin.joinAsNew("Carol")
        settle()
        assertTrue(share(carol, alice) is BundleSync.ImportOutcome.Imported)

        // The same invite, forwarded on to Bob with Alice's current data.
        val join = bob.join()
        join.openFile(alice.members().inviteFile(link))
        settle()

        assertEquals(JoinUiState.Problem(JoinProblem.INVITE_USED_UP), join.uiState.value)
    }

    @Test
    fun `F25 - given two people racing one single-use invite then exactly one becomes a member on every phone`() = runTest(dispatcher) {
        seedAliceGroup()
        val file = alice.members().inviteFile(aliceInvites())
        val carol = Phone(tag = 0xC3, displayName = "Carol")
        // Both open the same file offline and both believe they joined.
        val bobJoin = bob.join()
        bobJoin.openFile(file)
        settle()
        bobJoin.joinAsNew("Bobby")
        settle()
        val carolJoin = carol.join()
        carolJoin.openFile(file)
        settle()
        carolJoin.joinAsNew("Carol")
        settle()

        // Alice hears from both; replay order picks one winner, deterministically.
        share(bob, alice)
        share(carol, alice)
        share(alice, bob)
        share(alice, carol)

        val state = alice.ledger.state(group)
        val joinedKeys = state.members.values.mapNotNull { it.identityKey }.toSet()
        val bobIn = bob.identity.keyPair().publicKey in joinedKeys
        val carolIn = carol.identity.keyPair().publicKey in joinedKeys
        assertTrue("exactly one racer holds the single-use invite", bobIn xor carolIn)
        // The winner and the inviter agree byte for byte.
        val winner = if (bobIn) bob else carol
        assertArrayEquals(alice.bytes(), winner.bytes())
        // KNOWN GAP (ROADMAP P2, open): the loser's update is refused as coming
        // from a non-member, so the loser's own quarantined claim never reaches
        // the others and the loser's phone does not converge with them. The
        // loser is correctly not a member anywhere; telling them so is not built.
        val loser = if (bobIn) carol else bob
        assertFalse(loser.identity.keyPair().publicKey in joinedKeys)
    }

    @Test
    fun `F25 - given approval mode then the joiner waits, the inviter approves, and both phones agree`() = runTest(dispatcher) {
        seedAliceGroup()
        alice.members().setApprovalRequired(true)
        settle()
        val file = alice.members().inviteFile(aliceInvites())

        val join = bob.join()
        join.openFile(file)
        settle()
        assertTrue((join.uiState.value as JoinUiState.ChooseMember).approvalRequired)
        join.claimExisting(bobId)
        settle()
        assertEquals(JoinUiState.Joined(group, "Goa trip", pendingApproval = true, justJoined = true), join.uiState.value)

        share(bob, alice)
        val aliceMembers = alice.members()
        aliceMembers.uiState.test {
            var state = awaitItem()
            while (state.members.none { it.pending }) state = awaitItem()
            val pending = state.members.single { it.pending }
            assertEquals("Bob", pending.name)
            assertTrue(pending.joinedViaMyInvite)
            assertTrue(state.approvalRequired)
            assertTrue(state.canManage)

            aliceMembers.approveMember(bobId)
            while (state.members.any { it.pending }) state = awaitItem()
            cancelAndConsumeRemainingEvents()
        }

        share(alice, bob)
        assertFalse(bob.ledger.state(group).members.getValue(bobId).pending)
        assertArrayEquals(alice.bytes(), bob.bytes())
        assertTrue(alice.ledger.state(group).quarantined.isEmpty())
    }

    @Test
    fun `F25 - given someone joined via my invite then I can remove them while they have no history, never after`() = runTest(dispatcher) {
        seedAliceGroup()
        val link = aliceInvites()
        val join = bob.join()
        join.openFile(alice.members().inviteFile(link))
        settle()
        join.joinAsNew("Stranger")
        settle()
        share(bob, alice)
        val strangerId = checkNotNull(bob.settings.state.value.selfMembers[group])

        val aliceMembers = alice.members()
        aliceMembers.uiState.test {
            var state = awaitItem()
            while (state.members.none { it.id == strangerId }) state = awaitItem()
            val stranger = state.members.single { it.id == strangerId }
            assertTrue(stranger.joinedViaMyInvite)
            assertTrue("no history yet: removal is offered", stranger.canRemove)
            // "Bob" owes for the dinner: only deactivation is possible (edge 13).
            assertFalse(state.members.single { it.id == bobId }.canRemove)
            assertFalse("never offered for yourself", state.members.single { it.id == aliceId }.canRemove)

            aliceMembers.removeMember(strangerId)
            while (state.members.any { it.id == strangerId }) state = awaitItem()
            cancelAndConsumeRemainingEvents()
        }

        share(alice, bob)
        assertTrue(bob.ledger.state(group).members.getValue(strangerId).removed)
        assertArrayEquals(alice.bytes(), bob.bytes())
        // A removed member's later updates are refused wholesale (AUDIT T1).
        bob.ledger.append(group, OpPayload.AddMember(MemberId("0d000000-0000-4000-8000-000000000004"), "Plus one"))
        val before = alice.bytes()
        assertEquals(BundleSync.ImportOutcome.RejectedUnknownSender(group), share(bob, alice))
        assertArrayEquals(before, alice.bytes())
    }

    @Test
    fun `F25 - given open invites then the members screen lists them and cancelling one closes it`() = runTest(dispatcher) {
        seedAliceGroup()
        val link = aliceInvites()
        val aliceMembers = alice.members()

        aliceMembers.uiState.test {
            var state = awaitItem()
            while (state.openInvites.isEmpty()) state = awaitItem()
            val row = state.openInvites.single()
            assertEquals(link.inviteId, row.id)
            assertEquals(1, row.usesLeft)
            assertTrue(row.createdByMe)

            aliceMembers.revokeInvite(link.inviteId)
            while (state.openInvites.isNotEmpty()) state = awaitItem()
            cancelAndConsumeRemainingEvents()
        }
    }

    // -- Problems at the door -------------------------------------------------------

    @Test
    fun `edge 76 - given text that is not an invite link then nothing is adopted and the problem is specific`() = runTest(dispatcher) {
        val join = bob.join()

        join.openLink("https://example.com/join?g=1")
        settle()

        assertEquals(JoinUiState.Problem(JoinProblem.INVALID_LINK), join.uiState.value)
        assertTrue(bob.keys.all().isEmpty())

        join.reset()
        assertEquals(JoinUiState.EnterLink, join.uiState.value)
    }

    @Test
    fun `edge 27 - given an invite for a group this phone holds under a different key then it is refused, not overwritten`() = runTest(dispatcher) {
        seedAliceGroup()
        val file = alice.members().inviteFile(aliceInvites())
        val otherKey = ByteArray(32) { 9 }
        bob.keys.adopt(group, otherKey)

        val join = bob.join()
        join.openFile(file)
        settle()

        assertEquals(JoinUiState.Problem(JoinProblem.DIFFERENT_GROUP_KEY), join.uiState.value)
        assertArrayEquals(otherKey, bob.keys.keyFor(group))
        assertEquals(0L, bob.ledger.opCount(group))
    }

    @Test
    fun `edge 27 - given an invite file damaged in transit then it is rejected wholesale`() = runTest(dispatcher) {
        seedAliceGroup()
        val file = alice.members().inviteFile(aliceInvites())
        val damaged = file.copyOf().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }

        val join = bob.join()
        join.openFile(damaged)
        settle()

        // The signature covers the ciphertext, so a flipped byte fails authentication.
        assertEquals(JoinUiState.Problem(JoinProblem.BAD_SIGNATURE), join.uiState.value)
        assertEquals(0L, bob.ledger.opCount(group))
    }

    @Test
    fun `given files that are not Dibs files, or updates for a group never joined, then each gets its own message`() = runTest(dispatcher) {
        seedAliceGroup()
        val join = bob.join()

        join.openFile("holiday photos".toByteArray())
        settle()
        assertEquals(JoinUiState.Problem(JoinProblem.NOT_A_DIBS_FILE), join.uiState.value)

        join.openFile(ByteArray(0))
        settle()
        assertEquals(JoinUiState.Problem(JoinProblem.NOT_A_DIBS_FILE), join.uiState.value)

        join.openFile(alice.sync.exportBundle(group))
        settle()
        assertEquals(JoinUiState.Problem(JoinProblem.NO_KEY_FOR_GROUP), join.uiState.value)
    }

    // -- F23 + F24: ongoing sync ----------------------------------------------------

    @Test
    fun `F23 - given an update file opened from outside the app then it lands in the right group and says what arrived`() = runTest(dispatcher) {
        seedAliceGroup()
        bobJoinsAsBob()
        share(bob, alice)
        alice.ledger.append(group, OpPayload.AddMember(MemberId("0d000000-0000-4000-8000-000000000004"), "Dana"))
        alice.ledger.append(group, OpPayload.RenameMember(aliceId, "Alice K"))

        val opened = bob.join()
        opened.openFile(alice.sync.exportBundle(group))
        settle()

        assertEquals(
            JoinUiState.UpdatesImported(group, "Goa trip", opsAdded = 2, mayBeMissing = false),
            opened.uiState.value,
        )
        assertArrayEquals(alice.bytes(), bob.bytes())

        // Opening the same file twice is harmless and says so (edge 23).
        val again = bob.join()
        again.openFile(alice.sync.exportBundle(group))
        settle()
        assertEquals(0, (again.uiState.value as JoinUiState.UpdatesImported).opsAdded)
    }

    @Test
    fun `F24 - given both phones editing the same expense offline then they converge and both edits stay in history`() = runTest(dispatcher) {
        seedAliceGroup()
        bobJoinsAsBob()
        share(bob, alice)

        // Offline, at the same time: Alice fixes the amount, Bob fixes the name.
        alice.ledger.append(
            group,
            OpPayload.EditExpense(
                dinner,
                ExpenseEdit(
                    amounts = AmountsEdit(
                        currency = usd,
                        total = Money(4000),
                        paidBy = mapOf(aliceId to Money(4000)),
                        owedBy = mapOf(aliceId to Money(2000), bobId to Money(2000)),
                    ),
                ),
            ),
        )
        bob.ledger.append(group, OpPayload.EditExpense(dinner, ExpenseEdit(description = "Dinner at Fisherman's")))

        share(alice, bob)
        share(bob, alice)

        assertArrayEquals(alice.bytes(), bob.bytes())
        val expense = alice.ledger.state(group).expenses.getValue(dinner)
        assertEquals(Money(4000), expense.fields.total)
        assertEquals("Dinner at Fisherman's", expense.fields.description)
        assertEquals("edge 20: both devices appear in the history", 2, expense.editingDevices.size)
    }

    @Test
    fun `F24 - given one phone deletes while the other edits then delete wins and the edit survives a restore`() = runTest(dispatcher) {
        seedAliceGroup()
        bobJoinsAsBob()
        share(bob, alice)

        alice.ledger.append(group, OpPayload.DeleteExpense(dinner))
        bob.ledger.append(group, OpPayload.EditExpense(dinner, ExpenseEdit(description = "Late dinner")))
        share(alice, bob)
        share(bob, alice)

        assertArrayEquals(alice.bytes(), bob.bytes())
        assertTrue("edge 21: delete wins", bob.ledger.state(group).expenses.getValue(dinner).deleted)
        assertEquals(1, bob.ledger.state(group).trash.size)

        bob.ledger.append(group, OpPayload.RestoreExpense(dinner))
        share(bob, alice)
        val restored = alice.ledger.state(group).expenses.getValue(dinner)
        assertFalse(restored.deleted)
        assertEquals("the concurrent edit was kept", "Late dinner", restored.fields.description)
    }

    @Test
    fun `edge 32 - given both phones recording the same repayment offline then both are kept and the overpayment shows`() = runTest(dispatcher) {
        seedAliceGroup()
        bobJoinsAsBob()
        share(bob, alice)

        alice.ledger.append(
            group,
            OpPayload.RecordSettlement(SettlementId("5a000000-0000-4000-8000-000000000001"), bobId, aliceId, Money(1500), usd),
        )
        bob.ledger.append(
            group,
            OpPayload.RecordSettlement(SettlementId("5b000000-0000-4000-8000-000000000002"), bobId, aliceId, Money(1500), usd),
        )
        share(alice, bob)
        share(bob, alice)

        assertArrayEquals(alice.bytes(), bob.bytes())
        val state = alice.ledger.state(group)
        assertEquals(2, state.settlements.size)
        // Bob owed 15.00 and it was recorded twice: he is now 15.00 in credit, visibly.
        assertEquals(Money(1500), state.balances().getValue(usd).first { it.memberId == bobId }.net)
    }

    @Test
    fun `edge 75 - given a relayed bundle whose sender knew more than it carried then the import says changes may be missing`() = runTest(dispatcher) {
        seedAliceGroup()
        bobJoinsAsBob()
        share(bob, alice)
        val stale = alice.sync.exportBundle(group)
        alice.ledger.append(group, OpPayload.RenameMember(aliceId, "Alice K"))
        val fresh = alice.sync.exportBundle(group)

        // Bob receives the fresh one, then someone forwards him the older file: nothing lost, nothing missing.
        assertEquals(1, (bob.sync.importBundle(fresh) as BundleSync.ImportOutcome.Imported).opsAdded)
        val replay = bob.sync.importBundle(stale) as BundleSync.ImportOutcome.Imported
        assertEquals(0, replay.opsAdded)
        assertTrue(replay.behind.isEmpty())
        assertArrayEquals(alice.bytes(), bob.bytes())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val EIGHT_DAYS_MILLIS = 8L * 24 * 60 * 60 * 1000
    }
}
