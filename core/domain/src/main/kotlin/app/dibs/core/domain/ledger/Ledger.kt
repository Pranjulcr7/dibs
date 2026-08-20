// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.GroupId
import kotlinx.coroutines.flow.Flow

/**
 * The ledger as feature modules see it (SPEC §5.5: "the Ledger interface should
 * not leak the implementation"). Features depend only on `:core:domain`, so
 * this interface is their entire persistence surface; `:core:data` implements
 * it and `:app` binds it.
 *
 * [append] takes no wall clock: the implementation stamps it, because a clock
 * is an environmental detail (display-only by invariant 6) that ViewModels
 * should neither choose nor be able to get wrong.
 */
interface Ledger {

    /** Every group this device knows of; updates as groups are created or ingested. */
    fun observeGroupIds(): Flow<Set<GroupId>>

    /** The folded state of one group and every subsequent change. */
    fun observeState(groupId: GroupId): Flow<GroupState>

    suspend fun state(groupId: GroupId): GroupState

    /** Creates and persists a local op with the next lamport and current heads. */
    suspend fun append(groupId: GroupId, payload: OpPayload): Op

    /**
     * Appends many ops as one batch, in order. Semantically identical to calling
     * [append] in a loop — same lamport chaining, same fold — but implementations
     * may (and `:core:data` does) persist the batch with a single projection
     * write instead of one per op: rewriting the materialized state after every
     * op makes an n-expense import cost O(n²), which turned a 5,000-row Splitwise
     * import into minutes of writes on a real device. Bulk writers (import,
     * restore) must use this.
     */
    suspend fun appendAll(groupId: GroupId, payloads: List<OpPayload>): List<Op> = payloads.map { append(groupId, it) }

    /** Merges remote ops; duplicates are no-ops (case 23). */
    suspend fun ingest(groupId: GroupId, ops: Collection<Op>)

    /** Ops buffered for missing ancestors — the "syncing" indicator (case 24). */
    suspend fun pendingCount(groupId: GroupId): Int

    /**
     * Total applied ops across every group. The backup reminder (FR-I5) compares
     * this against the count captured at the last backup: "the ledger has
     * changed" is exactly "this number moved", with no clock involved.
     */
    suspend fun totalOpCount(): Long
}
