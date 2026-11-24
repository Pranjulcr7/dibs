// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.sync

import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.GroupLedger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FR-Y4: the sync chip is a pure function of what was shared and what exists now. */
class SyncStatusTest {

    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val device = DeviceId("aa000000-0000-4000-8000-000000000000")

    @Test
    fun `given a group never shared or imported then the status is never-shared however many ops it holds`() {
        assertEquals(SyncStatus.NeverShared, SyncStatus.of(SyncMeta(), currentOpCount = 0))
        assertEquals(SyncStatus.NeverShared, SyncStatus.of(SyncMeta(), currentOpCount = 250))
    }

    @Test
    fun `given ops appended since the last share then the status counts exactly the unshared ones`() {
        val meta = SyncMeta(sharedOpCount = 10, lastSharedAtMillis = 1_000)

        assertEquals(SyncStatus.ChangesToShare(3), SyncStatus.of(meta, currentOpCount = 13))
    }

    @Test
    fun `given nothing new since the last share then the status is up to date as of that share`() {
        val meta = SyncMeta(sharedOpCount = 10, lastSharedAtMillis = 1_000)

        assertEquals(SyncStatus.UpToDate(1_000), SyncStatus.of(meta, currentOpCount = 10))
    }

    @Test
    fun `given both a share and an import then up-to-date reports the later of the two`() {
        val meta = SyncMeta(sharedOpCount = 10, lastSharedAtMillis = 1_000, lastImportAtMillis = 5_000)

        assertEquals(SyncStatus.UpToDate(5_000), SyncStatus.of(meta, currentOpCount = 10))
    }

    @Test
    fun `given a group only ever imported into then local ops beyond the shared count are changes to share`() {
        // Joined a group, received 7 ops, shared nothing back yet: all 7 count
        // as unshared from this device, which is what prompts the first share.
        val meta = SyncMeta(sharedOpCount = 0, lastImportAtMillis = 2_000)

        assertEquals(SyncStatus.ChangesToShare(7), SyncStatus.of(meta, currentOpCount = 7))
    }

    @Test
    fun `given the no-op transport then it is manual, pushes succeed without moving anything, and pulls are empty`() =
        runBlocking {
            val ledger = GroupLedger(group, device)
            val op = ledger.append(OpPayload.AddMember(MemberId("0a000000-0000-4000-8000-000000000001"), "A"), 1)

            assertEquals(TransportCapability.MANUAL, NoOpTransport.capability)
            assertTrue(NoOpTransport.push(group, listOf(op)).isSuccess)
            assertEquals(emptyList<Any>(), NoOpTransport.pull(group, VectorClock.EMPTY).getOrThrow())
        }

    @Test
    fun `given import outcomes then each rejection is a distinct value the UI can explain`() {
        // FR-Y4 "never a silent failure" rests on the outcomes being
        // distinguishable: two reasons collapsing into one would mean one
        // message standing in for two different problems.
        val outcomes: List<BundleSync.ImportOutcome> = listOf(
            BundleSync.ImportOutcome.Imported(group, opsAdded = 2, skippedUnknownOps = 0, behind = emptyMap()),
            BundleSync.ImportOutcome.NoKeyForGroup(group),
            BundleSync.ImportOutcome.NotABundle,
            BundleSync.ImportOutcome.NeedsNewerApp(theirMajor = 2),
            BundleSync.ImportOutcome.RejectedTamperedOrWrongKey,
            BundleSync.ImportOutcome.RejectedBadSignature,
            BundleSync.ImportOutcome.RejectedUnknownSender(group),
        )

        assertEquals(outcomes.size, outcomes.toSet().size)
        val behind = BundleSync.ImportOutcome.Imported(group, 2, 0, behind = mapOf(device to 4L))
        assertNotEquals(outcomes.first(), behind)
        assertEquals(mapOf(device to 4L), behind.behind)
    }
}
