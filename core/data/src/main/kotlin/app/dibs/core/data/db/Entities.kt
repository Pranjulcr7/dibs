// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per op — the **source of truth**. Everything else in this database is
 * a disposable projection of this table; dropping and refolding the caches must
 * always be safe (SPEC §5.5: state is a fold over the log).
 *
 * The payload is stored as JSON, but op identity is never derived from that
 * JSON — ids come from the domain's canonical encoding, so a serialization
 * change cannot corrupt identity.
 */
@Entity(
    tableName = "ops",
    indices = [Index("groupId"), Index("groupId", "lamport")],
)
data class OpEntity(
    @PrimaryKey val opId: String,
    val groupId: String,
    val lamport: Long,
    val deviceId: String,
    val parentsJson: String,
    val wallClockMillis: Long,
    val payloadJson: String,
)

/** Materialized member row for direct UI queries; rebuilt from folds, never edited directly. */
@Entity(tableName = "members", primaryKeys = ["groupId", "memberId"])
data class MemberEntity(
    val groupId: String,
    val memberId: String,
    val name: String,
    val active: Boolean,
)

/** Materialized expense row; `fieldsJson`/`historyJson` carry the folded domain state. */
@Entity(tableName = "expenses", primaryKeys = ["groupId", "expenseId"])
data class ExpenseEntity(
    val groupId: String,
    val expenseId: String,
    val deleted: Boolean,
    val fieldsJson: String,
    val historyJson: String,
)

/** Materialized settlement row. */
@Entity(tableName = "settlements", primaryKeys = ["groupId", "settlementId"])
data class SettlementEntity(
    val groupId: String,
    val settlementId: String,
    val fromMemberId: String,
    val toMemberId: String,
    val amountMinorUnits: Long,
    val currency: String,
)

/**
 * Where the incremental fold left off for a group: the count of applied ops and
 * the id of the last op in replay order. Ops are append-only, so "the op at
 * position count−1 still has this id" is a complete O(1) check that the folded
 * prefix is unchanged (see LedgerRepository).
 */
@Entity(tableName = "fold_meta")
data class FoldMetaEntity(
    @PrimaryKey val groupId: String,
    val appliedOpCount: Int,
    val lastAppliedOpId: String?,
)

/**
 * One serialized [app.dibs.core.domain.ledger.GroupState] per group — the
 * cold-open snapshot (ISSUES #19). Ops are append-only, so
 * `opRowCount == COUNT(ops for group)` proves the snapshot is current without
 * reading a single op row; any mismatch (or codec version drift) falls back to
 * a full refold from the ops table, which stays the only source of truth.
 * `headsJson`/`maxLamport` let appends chain correctly before the op log has
 * been loaded.
 */
@Entity(tableName = "state_snapshots")
data class StateSnapshotEntity(
    @PrimaryKey val groupId: String,
    val codecVersion: Int,
    val opRowCount: Long,
    val appliedOpCount: Int,
    val lastAppliedOpId: String?,
    val maxLamport: Long,
    val headsJson: String,
    val stateJson: String,
)
