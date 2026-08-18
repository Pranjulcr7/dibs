// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.ledger

import app.dibs.core.data.db.DibsDatabase
import app.dibs.core.data.db.ExpenseEntity
import app.dibs.core.data.db.FoldMetaEntity
import app.dibs.core.data.db.MemberEntity
import app.dibs.core.data.db.SettlementEntity
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.GroupProjector
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.Op
import app.dibs.core.domain.ledger.OpFactory
import app.dibs.core.domain.ledger.OpLog
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The op-log's persistence and projection engine: appends local ops, ingests
 * remote ones, keeps the Room cache tables in sync with the fold, and exposes
 * state as a Flow.
 *
 * **Incremental invalidation.** The projector is a pure left fold, so the
 * cached [GroupState] plus the position of the last folded op is a resumable
 * checkpoint. Ops are only ever *added*; therefore the folded prefix is
 * unchanged if and only if the op at the checkpoint position still has the
 * checkpoint's id — an O(1) test. New ops that all sort after the checkpoint
 * fold as a suffix; anything sorting earlier invalidates the checkpoint and
 * triggers a full refold. Both paths land on identical state by construction
 * (same fold, same total order) and the tests assert it.
 */
@Suppress("TooManyFunctions") // the Ledger interface surface plus its internals; cohesive by design
class LedgerRepository(
    private val db: DibsDatabase,
    private val deviceId: DeviceId,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : Ledger {

    /** Fold-path counters, exposed for tests to prove which path ran. */
    data class Diagnostics(val incrementalFolds: Int = 0, val fullRefolds: Int = 0)

    var diagnostics = Diagnostics()
        internal set

    private data class GroupCache(
        val log: OpLog,
        val state: GroupState,
        val appliedCount: Int,
        val lastAppliedOpId: String?,
    )

    private val mutex = Mutex()
    private val caches = HashMap<GroupId, GroupCache>()
    private val groupIdsFlow = MutableStateFlow<Set<GroupId>?>(null)
    private val stateFlows = HashMap<GroupId, MutableStateFlow<GroupState?>>()
    private val json = Json

    /** [Ledger.append]: the repository stamps the (display-only) wall clock itself. */
    override suspend fun append(groupId: GroupId, payload: OpPayload): Op = append(groupId, payload, wallClock())

    /** Creates a local op with the next lamport and current heads, persists it, and refolds. */
    suspend fun append(groupId: GroupId, payload: OpPayload, wallClockMillis: Long): Op = mutex.withLock {
        val cache = loadLocked(groupId)
        val op = OpFactory.create(
            groupId = groupId,
            deviceId = deviceId,
            lamport = cache.log.maxLamport + 1,
            parents = cache.log.heads,
            wallClockMillis = wallClockMillis,
            payload = payload,
        )
        mergeLocked(groupId, cache, listOf(op))
        op
    }

    /** Merges remote ops; duplicates are no-ops, ops for other groups are ignored. */
    override suspend fun ingest(groupId: GroupId, ops: Collection<Op>) {
        mutex.withLock {
            mergeLocked(groupId, loadLocked(groupId), ops.filter { it.groupId == groupId })
        }
    }

    override suspend fun state(groupId: GroupId): GroupState = mutex.withLock { loadLocked(groupId).state }

    /** Ops buffered for missing ancestors — the "syncing" indicator (case 24). */
    override suspend fun pendingCount(groupId: GroupId): Int = mutex.withLock { loadLocked(groupId).log.pending.size }

    /** Every op known for the group (for sync and for reference folds in tests). */
    suspend fun allOps(groupId: GroupId): List<Op> = mutex.withLock { loadLocked(groupId).log.all.toList() }

    override fun observeState(groupId: GroupId): Flow<GroupState> = flow {
        mutex.withLock { loadLocked(groupId) }
        emitAll(flowFor(groupId).filterNotNull())
    }

    /** [Ledger.observeGroupIds]: seeded from the ops table, updated on every append/ingest. */
    override fun observeGroupIds(): Flow<Set<GroupId>> = flow {
        ensureGroupIdsLoaded()
        emitAll(groupIdsFlow.filterNotNull())
    }

    // -- internals ----------------------------------------------------------

    private fun flowFor(groupId: GroupId): MutableStateFlow<GroupState?> =
        synchronized(stateFlows) { stateFlows.getOrPut(groupId) { MutableStateFlow(null) } }

    private suspend fun ensureGroupIdsLoaded() {
        if (groupIdsFlow.value == null) {
            mutex.withLock {
                if (groupIdsFlow.value == null) {
                    groupIdsFlow.value = db.opDao().groupIds().map(::GroupId).toSet()
                }
            }
        }
    }

    private fun noteGroupLocked(groupId: GroupId) {
        groupIdsFlow.value = (groupIdsFlow.value ?: emptySet()) + groupId
    }

    private suspend fun loadLocked(groupId: GroupId): GroupCache {
        caches[groupId]?.let { return it }
        val log = OpLog.empty().merge(db.opDao().forGroup(groupId.value).map(OpCodec::fromEntity))
        val applicable = log.applicable
        val state = GroupProjector.project(groupId, applicable)
        val cache = GroupCache(log, state, applicable.size, applicable.lastOrNull()?.id?.value)
        caches[groupId] = cache
        flowFor(groupId).value = state
        return cache
    }

    private suspend fun mergeLocked(groupId: GroupId, cache: GroupCache, ops: List<Op>) {
        val newLog = cache.log.merge(ops)
        if (newLog === cache.log) return

        db.opDao().insertAll(ops.map(OpCodec::toEntity))

        val applicable = newLog.applicable
        val checkpointIntact = cache.appliedCount == 0 ||
            (
                applicable.size >= cache.appliedCount &&
                    applicable[cache.appliedCount - 1].id.value == cache.lastAppliedOpId
                )
        val newState = if (checkpointIntact) {
            diagnostics = diagnostics.copy(incrementalFolds = diagnostics.incrementalFolds + 1)
            applicable.subList(cache.appliedCount, applicable.size)
                .fold(cache.state) { state, op -> GroupProjector.apply(state, op) }
        } else {
            diagnostics = diagnostics.copy(fullRefolds = diagnostics.fullRefolds + 1)
            GroupProjector.project(groupId, applicable)
        }

        persistProjection(groupId, newState, applicable.size, applicable.lastOrNull()?.id?.value)
        caches[groupId] = GroupCache(newLog, newState, applicable.size, applicable.lastOrNull()?.id?.value)
        flowFor(groupId).value = newState
        noteGroupLocked(groupId)
    }

    private suspend fun persistProjection(
        groupId: GroupId,
        state: GroupState,
        appliedCount: Int,
        lastAppliedOpId: String?,
    ) {
        db.stateDao().rewriteGroupProjection(
            groupId = groupId.value,
            members = state.members.values.map {
                MemberEntity(groupId.value, it.id.value, it.name, it.active)
            },
            expenses = state.expenses.values.map {
                ExpenseEntity(
                    groupId = groupId.value,
                    expenseId = it.id.value,
                    deleted = it.deleted,
                    fieldsJson = json.encodeToString<ExpenseFields>(it.fields),
                    historyJson = json.encodeToString(it.history.map { event -> event.opId.value }),
                )
            },
            settlements = state.settlements.values.map {
                SettlementEntity(
                    groupId = groupId.value,
                    settlementId = it.id.value,
                    fromMemberId = it.from.value,
                    toMemberId = it.to.value,
                    amountMinorUnits = it.amount.minorUnits,
                    currency = it.currency.value,
                )
            },
            meta = FoldMetaEntity(groupId.value, appliedCount, lastAppliedOpId),
        )
    }
}
