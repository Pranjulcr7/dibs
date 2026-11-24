// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.sync.BundleSync
import app.dibs.feature.groups.detail.SyncSharing
import app.dibs.feature.groups.detail.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncSharingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val group = GroupId("11111111-0000-4000-8000-000000000000")

    @Test
    fun `given group names then the update file name is recognisable, safe, and always carries the extension`() {
        assertEquals("dibs-trip-to-goa.dibssync", SyncSharing.fileName("Trip to Goa"))
        assertEquals("dibs-flat-4b.dibssync", SyncSharing.fileName("  Flat #4B!! "))
        // Path separators and dots can never escape the staging directory.
        assertEquals("dibs-etc-passwd.dibssync", SyncSharing.fileName("../../etc/passwd"))
        assertEquals("dibs-updates.dibssync", SyncSharing.fileName(null))
        assertEquals("dibs-updates.dibssync", SyncSharing.fileName("🎒"))
        assertTrue(SyncSharing.fileName("x".repeat(500)).length < 64)
    }

    @Test
    fun `FR-Y4 - given every import outcome then each has its own non-empty message`() {
        val outcomes = listOf(
            BundleSync.ImportOutcome.Imported(group, opsAdded = 3, skippedUnknownOps = 0, behind = emptyMap()),
            BundleSync.ImportOutcome.Imported(
                group,
                opsAdded = 3,
                skippedUnknownOps = 0,
                behind = mapOf(DeviceId("bb000000-0000-4000-8000-000000000000") to 2L),
            ),
            BundleSync.ImportOutcome.NoKeyForGroup(group),
            BundleSync.ImportOutcome.NotABundle,
            BundleSync.ImportOutcome.NeedsNewerApp(theirMajor = 2),
            BundleSync.ImportOutcome.RejectedTamperedOrWrongKey,
            BundleSync.ImportOutcome.RejectedBadSignature,
            BundleSync.ImportOutcome.RejectedUnknownSender(group),
        )

        val messages = outcomes.map { it.message(context) }

        assertTrue(messages.all { it.isNotBlank() })
        assertEquals("no two outcomes may share a message", messages.size, messages.toSet().size)
        assertEquals("Updates added: 3 new changes.", messages[0])
        assertTrue(messages[1].contains("may still be missing"))
    }
}
