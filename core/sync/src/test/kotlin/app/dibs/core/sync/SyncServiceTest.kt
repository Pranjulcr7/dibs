// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.sync

import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.crypto.IdentityKeyPair
import app.dibs.core.domain.crypto.IdentitySource
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.GroupLedger
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.Op
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.ledger.SignedPayloads
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.sync.GroupKeyStore
import app.dibs.core.domain.sync.SyncMeta
import app.dibs.core.domain.sync.SyncMetaStore
import app.dibs.core.domain.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncServiceTest {

    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")

    // -- lightweight fakes over the pure domain ledger ------------------------

    private class FakeLedger(private val deviceId: DeviceId) : Ledger {
        val ledgers = LinkedHashMap<GroupId, GroupLedger>()
        private var clock = 1_000L

        fun ledgerFor(id: GroupId) = ledgers.getOrPut(id) { GroupLedger(id, deviceId) }

        override fun observeGroupIds(): Flow<Set<GroupId>> = MutableStateFlow(ledgers.keys.toSet())

        override fun observeState(groupId: GroupId): Flow<GroupState> =
            MutableStateFlow(ledgerFor(groupId).state())

        override suspend fun state(groupId: GroupId): GroupState = ledgerFor(groupId).state()

        override suspend fun append(groupId: GroupId, payload: OpPayload): Op =
            ledgerFor(groupId).append(payload, ++clock)

        override suspend fun ingest(groupId: GroupId, ops: Collection<Op>) {
            ledgerFor(groupId).receive(ops)
        }

        override suspend fun pendingCount(groupId: GroupId): Int = ledgerFor(groupId).log.pending.size

        override suspend fun totalOpCount(): Long = ledgers.values.sumOf { it.log.all.size.toLong() }

        override suspend fun allOps(groupId: GroupId): List<Op> = ledgerFor(groupId).log.all.toList()
    }

    private class FakeKeys : GroupKeyStore {
        val keys = HashMap<GroupId, ByteArray>()
        override suspend fun keyFor(groupId: GroupId): ByteArray? = keys[groupId]

        override suspend fun getOrCreate(groupId: GroupId): ByteArray =
            keys.getOrPut(groupId) { ByteArray(32) { it.toByte() } }

        override suspend fun adopt(groupId: GroupId, key: ByteArray) {
            val held = keys.putIfAbsent(groupId, key)
            check(held == null || held.contentEquals(key)) { "different key" }
        }

        override suspend fun all(): Map<GroupId, ByteArray> = keys.toMap()
    }

    private class FakeMeta : SyncMetaStore {
        val metas = HashMap<GroupId, SyncMeta>()
        override suspend fun metaFor(groupId: GroupId): SyncMeta = metas[groupId] ?: SyncMeta()

        override suspend fun recordShared(groupId: GroupId, opCount: Long, atMillis: Long) {
            metas[groupId] = metaFor(groupId).copy(sharedOpCount = opCount, lastSharedAtMillis = atMillis)
        }

        override suspend fun recordImported(groupId: GroupId, atMillis: Long) {
            metas[groupId] = metaFor(groupId).copy(lastImportAtMillis = atMillis)
        }
    }

    private fun identity(seed: Byte): Pair<IdentityKeyPair, IdentitySource> {
        val pair = Identity.keyPairFromMaster(ByteArray(32) { seed })
        return pair to object : IdentitySource {
            override suspend fun keyPair(): IdentityKeyPair = pair
        }
    }

    private class Device(val ledger: FakeLedger, val keys: FakeKeys, val meta: FakeMeta, val sync: SyncService)

    private fun device(tag: Byte, keyStore: FakeKeys = FakeKeys()): Device {
        val deviceId = DeviceId("%02x000000-0000-4000-8000-000000000000".format(tag))
        val ledger = FakeLedger(deviceId)
        val meta = FakeMeta()
        val (_, source) = identity(tag)
        return Device(ledger, keyStore, meta, SyncService(ledger, keyStore, meta, source, deviceId) { 5_000L })
    }

    private suspend fun seedClaimedGroup(device: Device, claimant: IdentityKeyPair) {
        device.ledger.append(group, OpPayload.CreateGroup("Trip", defaultCurrency = CurrencyCode("EUR")))
        device.ledger.append(group, OpPayload.AddMember(alice, "Alice"))
        device.ledger.append(group, OpPayload.AddMember(bob, "Bob"))
        device.ledger.append(group, SignedPayloads.claimMember(group, alice, claimant))
        device.ledger.append(
            group,
            OpPayload.AddExpense(
                ExpenseId("e1"),
                ExpenseFields(
                    description = "Dinner",
                    currency = CurrencyCode("EUR"),
                    total = Money(3000),
                    paidBy = mapOf(alice to Money(3000)),
                    owedBy = mapOf(alice to Money(1500), bob to Money(1500)),
                    dateEpochDay = 20_100,
                ),
            ),
        )
    }

    @Test
    fun `given a member's bundle then the joiner folds to byte-identical state and both chips settle`() = runTest {
        val (alicePair, _) = identity(3)
        val sharedKeys = FakeKeys()
        val sender = device(3, sharedKeys)
        seedClaimedGroup(sender, alicePair)

        // FR-Y4 chip walk: never shared → up to date → changes to share.
        val before = SyncStatus.of(sender.meta.metaFor(group), sender.ledger.allOps(group).size.toLong())
        assertEquals(SyncStatus.NeverShared, before)

        val bundle = sender.sync.exportBundle(group)
        val after = SyncStatus.of(sender.meta.metaFor(group), sender.ledger.allOps(group).size.toLong())
        assertEquals(SyncStatus.UpToDate(5_000L), after)

        // The receiver got the key through the invite link (adopt), not the bundle.
        val receiver = device(4, sharedKeys)
        val outcome = receiver.sync.importBundle(bundle) as SyncService.ImportOutcome.Imported
        assertEquals(5, outcome.opsAdded)
        assertEquals(0, outcome.skippedUnknownOps)
        assertTrue(outcome.behind.isEmpty())
        assertArrayEquals(
            sender.ledger.state(group).canonicalBytes(),
            receiver.ledger.state(group).canonicalBytes(),
        )

        // New local work re-arms the chip with an exact count.
        sender.ledger.append(group, OpPayload.RenameMember(bob, "Robert"))
        val drifted = SyncStatus.of(sender.meta.metaFor(group), sender.ledger.allOps(group).size.toLong())
        assertEquals(SyncStatus.ChangesToShare(1), drifted)
    }

    @Test
    fun `T1 - given a bundle signed by a non-member holding the group key then it is rejected and nothing applies`() = runTest {
        val (alicePair, _) = identity(3)
        val sharedKeys = FakeKeys()
        val sender = device(3, sharedKeys)
        seedClaimedGroup(sender, alicePair)
        sender.sync.exportBundle(group)

        // An outsider (device 9, unclaimed identity) somehow has the key and
        // re-seals the group's ops plus a forged expense under their own name.
        val outsider = device(9, sharedKeys)
        outsider.ledger.ingest(group, sender.ledger.allOps(group))
        outsider.ledger.append(
            group,
            OpPayload.AddExpense(
                ExpenseId("forged"),
                ExpenseFields(
                    description = "totally real",
                    currency = CurrencyCode("EUR"),
                    total = Money(99_00),
                    paidBy = mapOf(bob to Money(99_00)),
                    owedBy = mapOf(alice to Money(99_00)),
                    dateEpochDay = 20_101,
                ),
            ),
        )
        val forged = outsider.sync.exportBundle(group)

        val receiver = device(4, sharedKeys)
        receiver.sync.importBundle(sender.sync.exportBundle(group))
        val beforeBytes = receiver.ledger.state(group).canonicalBytes()

        val outcome = receiver.sync.importBundle(forged)

        assertEquals(SyncService.ImportOutcome.RejectedUnknownSender(group), outcome)
        assertArrayEquals(beforeBytes, receiver.ledger.state(group).canonicalBytes())
    }

    @Test
    fun `given a group with no claimed members then bundles apply ungated — identity stays opt-in`() = runTest {
        val sharedKeys = FakeKeys()
        val sender = device(3, sharedKeys)
        sender.ledger.append(group, OpPayload.CreateGroup("Trip", defaultCurrency = CurrencyCode("EUR")))
        sender.ledger.append(group, OpPayload.AddMember(alice, "Alice"))
        val bundle = sender.sync.exportBundle(group)

        val receiver = device(4, sharedKeys)
        val outcome = receiver.sync.importBundle(bundle)

        assertTrue(outcome is SyncService.ImportOutcome.Imported)
    }

    @Test
    fun `given a bundle for a group we hold no key for then the outcome routes to the join flow`() = runTest {
        val (alicePair, _) = identity(3)
        val sender = device(3)
        seedClaimedGroup(sender, alicePair)
        val bundle = sender.sync.exportBundle(group)

        val receiver = device(4) // separate, empty key store
        assertEquals(SyncService.ImportOutcome.NoKeyForGroup(group), receiver.sync.importBundle(bundle))
    }

    @Test
    fun `given the same bundle imported twice then the second adds nothing — idempotent by content address`() = runTest {
        val (alicePair, _) = identity(3)
        val sharedKeys = FakeKeys()
        val sender = device(3, sharedKeys)
        seedClaimedGroup(sender, alicePair)
        val bundle = sender.sync.exportBundle(group)

        val receiver = device(4, sharedKeys)
        receiver.sync.importBundle(bundle)
        val second = receiver.sync.importBundle(bundle) as SyncService.ImportOutcome.Imported

        assertEquals(0, second.opsAdded)
    }

    @Test
    fun `given garbage then import says not-a-bundle`() = runTest {
        val receiver = device(4)
        assertEquals(
            SyncService.ImportOutcome.NotABundle,
            receiver.sync.importBundle("junk mail".toByteArray()),
        )
    }
}
