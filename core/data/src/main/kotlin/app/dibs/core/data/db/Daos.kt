// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface OpDao {

    /** IGNORE, not REPLACE: ops are immutable and content-addressed; a duplicate is a no-op (case 23). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(ops: List<OpEntity>): List<Long>

    @Query("SELECT * FROM ops WHERE groupId = :groupId")
    suspend fun forGroup(groupId: String): List<OpEntity>

    @Query("SELECT COUNT(*) FROM ops WHERE groupId = :groupId")
    suspend fun countForGroup(groupId: String): Long

    @Query("SELECT DISTINCT groupId FROM ops")
    suspend fun groupIds(): List<String>

    @Query("SELECT COUNT(*) FROM ops")
    suspend fun countAll(): Long
}

@Dao
@Suppress("TooManyFunctions") // one query + one upsert + one clear per projection table, plus the transaction
interface StateDao {

    @Query("SELECT * FROM members WHERE groupId = :groupId ORDER BY memberId")
    suspend fun membersForGroup(groupId: String): List<MemberEntity>

    @Query("SELECT * FROM expenses WHERE groupId = :groupId ORDER BY expenseId")
    suspend fun expensesForGroup(groupId: String): List<ExpenseEntity>

    @Query("SELECT * FROM settlements WHERE groupId = :groupId ORDER BY settlementId")
    suspend fun settlementsForGroup(groupId: String): List<SettlementEntity>

    @Query("SELECT * FROM fold_meta WHERE groupId = :groupId")
    suspend fun foldMeta(groupId: String): FoldMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<MemberEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExpenses(expenses: List<ExpenseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettlements(settlements: List<SettlementEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFoldMeta(meta: FoldMetaEntity)

    @Query("DELETE FROM members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: String)

    @Query("DELETE FROM expenses WHERE groupId = :groupId")
    suspend fun clearExpenses(groupId: String)

    @Query("DELETE FROM settlements WHERE groupId = :groupId")
    suspend fun clearSettlements(groupId: String)

    @Query("SELECT * FROM state_snapshots WHERE groupId = :groupId")
    suspend fun snapshot(groupId: String): StateSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: StateSnapshotEntity)

    /**
     * Full rewrite of a group's projection in one transaction: the cache tables
     * are never allowed to hold a state no fold produced (edge 65's "fully
     * applied or not at all", applied to projections). The snapshot rides in
     * the same transaction so it can never disagree with the row projection.
     */
    @Transaction
    @Suppress("LongParameterList") // one atomic projection write: every table a fold produces
    suspend fun rewriteGroupProjection(
        groupId: String,
        members: List<MemberEntity>,
        expenses: List<ExpenseEntity>,
        settlements: List<SettlementEntity>,
        meta: FoldMetaEntity,
        snapshot: StateSnapshotEntity,
    ) {
        clearMembers(groupId)
        clearExpenses(groupId)
        clearSettlements(groupId)
        upsertMembers(members)
        upsertExpenses(expenses)
        upsertSettlements(settlements)
        upsertFoldMeta(meta)
        upsertSnapshot(snapshot)
    }
}
