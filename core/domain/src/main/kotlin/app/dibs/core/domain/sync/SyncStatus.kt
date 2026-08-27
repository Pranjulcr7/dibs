// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.sync

import app.dibs.core.domain.model.GroupId

/**
 * What this device remembers about sharing a group (FR-Y4). Counts, not
 * clocks, decide "changed": the op log is append-only, so `sharedOpCount`
 * moving is exactly "there is something new to share" — the same trick the
 * backup reminder uses (D36). Wall-clock stamps are display-only.
 */
data class SyncMeta(
    val sharedOpCount: Long = 0L,
    val lastSharedAtMillis: Long? = null,
    val lastImportAtMillis: Long? = null,
)

/** Persistence for [SyncMeta]; implemented over the encrypted local store. */
interface SyncMetaStore {

    suspend fun metaFor(groupId: GroupId): SyncMeta

    suspend fun recordShared(groupId: GroupId, opCount: Long, atMillis: Long)

    suspend fun recordImported(groupId: GroupId, atMillis: Long)
}

/**
 * The per-group sync chip (FR-Y4: "Up to date", "3 changes to share",
 * "Last synced 2 days ago" — never a silent failure). Derived, never stored:
 * always a pure function of the meta and the current op count.
 */
sealed interface SyncStatus {

    /** The group has never been shared from this device — invite flow territory. */
    data object NeverShared : SyncStatus

    /** Local ops the last shared bundle did not carry. */
    data class ChangesToShare(val count: Long) : SyncStatus

    /** Everything local has been shared; [lastActivityAtMillis] feeds "last synced …". */
    data class UpToDate(val lastActivityAtMillis: Long) : SyncStatus

    companion object {
        fun of(meta: SyncMeta, currentOpCount: Long): SyncStatus = when {
            meta.lastSharedAtMillis == null && meta.lastImportAtMillis == null -> NeverShared
            currentOpCount > meta.sharedOpCount -> ChangesToShare(currentOpCount - meta.sharedOpCount)
            else -> UpToDate(maxOf(meta.lastSharedAtMillis ?: 0L, meta.lastImportAtMillis ?: 0L))
        }
    }
}
