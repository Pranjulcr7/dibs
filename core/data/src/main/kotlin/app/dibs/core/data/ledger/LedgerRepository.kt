// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.ledger

import app.dibs.core.data.db.DibsDatabase
import app.dibs.core.data.db.ExpenseEntity
import app.dibs.core.data.db.FoldMetaEntity
import app.dibs.core.data.db.MemberEntity
import app.dibs.core.data.db.SettlementEntity
import app.dibs.core.data.db.StateSnapshotEntity
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.GroupProjector
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.Op
import app.dibs.core.domain.ledger.OpFactory
import app.dibs.core.domain.ledger.OpId
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
    data class Diagnostics(
        val incrementalFolds: Int = 0,
        val fullRefolds: Int = 0,
        val projectionWrites: Int = 0,
        val snapshotRestores: Int = 0,
    )

    var diagnostics = Diagnostics()
        internal set

    /**
     * [log] is null after a snapshot restore: reads never need it, and the
     * first write loads it via [withLogLocked]. [heads]/[maxLamport] are kept
     * alongside so appends can chain before the log exists.
     */
    private data class GroupCache(
        val log: OpLog?,
        val state: GroupState,
        val appliedCount: Int,
        val lastAppliedOpId: String?,
        val heads: Set<OpId>,
        val maxLamport: Long,
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
        val cache = withLogLocked(groupId)
        val op = OpFactory.create(
            groupId = groupId,
            deviceId = deviceId,
            lamport = cache.maxLamport + 1,
            parents = cache.heads,
            wallClockMillis = wallClockMillis,
            payload = payload,
        )
        mergeLocked(groupId, cache, listOf(op))
        op
    }

    /**
     * [Ledger.appendAll]: the batch shares one lock, one insert, one fold pass,
     * and one projection write. The per-op default would rewrite the whole
     * materialized projection after every op — O(n²) for an n-row import, which
     * the 5,000-expense device test (AUDIT T7) measured in minutes, not ms.
     */
    override suspend fun appendAll(groupId: GroupId, payloads: List<OpPayload>): List<Op> = mutex.withLock {
        val cache = withLogLocked(groupId)
        var lamport = cache.maxLamport
        var heads = cache.heads
        val ops = payloads.map { payload ->
            val op = OpFactory.create(
                groupId = groupId,
                deviceId = deviceId,
                lamport = ++lamport,
                parents = heads,
                wallClockMillis = wallClock(),
                payload = payload,
            )
            heads = setOf(op.id)
            op
        }
        mergeLocked(groupId, cache, ops)
        ops
    }

    /** Merges remote ops; duplicates are no-ops, ops for other groups are ignored. */
    override suspend fun ingest(groupId: GroupId, ops: Collection<Op>) {
        mutex.withLock {
            mergeLocked(groupId, withLogLocked(groupId), ops.filter { it.groupId == groupId })
        }
    }

    override suspend fun state(groupId: GroupId): GroupState = mutex.withLock { loadLocked(groupId).state }

    /** Ops buffered for missing ancestors — the "syncing" indicator (case 24). */
    override suspend fun pendingCount(groupId: GroupId): Int = mutex.withLock {
        checkNotNull(withLogLocked(groupId).log).pending.size
    }

    override suspend fun totalOpCount(): Long = db.opDao().countAll()

    /** Every op known for the group (for sync and for reference folds in tests). */
    suspend fun allOps(groupId: GroupId): List<Op> = mutex.withLock {
        checkNotNull(withLogLocked(groupId).log).all.toList()
    }

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
        return restoreFromSnapshotLocked(groupId) ?: fullLoadLocked(groupId)
    }

    /**
     * The cold-open fast path (ISSUES #19): decode one state blob instead of
     * folding every op. Ops are append-only, so a matching row count proves the
     * snapshot current; anything off — count, codec version, undecodable blob —
     * falls back to [fullLoadLocked], because the op log is the source of truth
     * and a snapshot is only ever a cache.
     */
    private suspend fun restoreFromSnapshotLocked(groupId: GroupId): GroupCache? {
        val snapshot = db.stateDao().snapshot(groupId.value) ?: return null
        if (snapshot.codecVersion != GroupStateCodec.VERSION) return null
        if (snapshot.opRowCount != db.opDao().countForGroup(groupId.value)) return null
        val state = decodeSnapshotOrNull(groupId, snapshot.stateJson) ?: return null
        val heads = json.decodeFromString<List<String>>(snapshot.headsJson).map(::OpId).toSet()
        diagnostics = diagnostics.copy(snapshotRestores = diagnostics.snapshotRestores + 1)
        val cache = GroupCache(
            log = null,
            state = state,
            appliedCount = snapshot.appliedOpCount,
            lastAppliedOpId = snapshot.lastAppliedOpId,
            heads = heads,
            maxLamport = snapshot.maxLamport,
        )
        caches[groupId] = cache
        flowFor(groupId).value = state
        return cache
    }

    /**
     * A snapshot that fails to parse is not an error to surface: it is a stale
     * cache, and the recovery is the same full refold that would have happened
     * had it never existed. Nothing is lost — the op log holds the truth.
     */
    @Suppress("SwallowedException")
    private fun decodeSnapshotOrNull(groupId: GroupId, stateJson: String): GroupState? = try {
        GroupStateCodec.decode(groupId, stateJson)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: kotlinx.serialization.SerializationException) {
        null
    }

    private suspend fun fullLoadLocked(groupId: GroupId): GroupCache {
        val log = OpLog.empty().merge(db.opDao().forGroup(groupId.value).map(OpCodec::fromEntity))
        val applicable = log.applicable
        val state = GroupProjector.project(groupId, applicable)
        val cache =
            GroupCache(log, state, applicable.size, applicable.lastOrNull()?.id?.value, log.heads, log.maxLamport)
        caches[groupId] = cache
        flowFor(groupId).value = state
        // A fold this expensive is worth one write: the next cold open restores
        // instead (covers first runs after the migration and stale snapshots).
        if (applicable.isNotEmpty()) {
            db.stateDao().upsertSnapshot(
                snapshotEntity(groupId, state, applicable.size, applicable.lastOrNull()?.id?.value, log),
            )
        }
        return cache
    }

    /** Loads the op log for a snapshot-restored cache; reads never pay this cost. */
    private suspend fun withLogLocked(groupId: GroupId): GroupCache {
        val cache = loadLocked(groupId)
        if (cache.log != null) return cache
        val log = OpLog.empty().merge(db.opDao().forGroup(groupId.value).map(OpCodec::fromEntity))
        val applicable = log.applicable
        val intact = applicable.size == cache.appliedCount &&
            applicable.lastOrNull()?.id?.value == cache.lastAppliedOpId
        val updated = if (intact) {
            cache.copy(log = log)
        } else {
            // The snapshot's count gate should make this unreachable; if it ever
            // trips, the answer is the same as everywhere else — refold.
            diagnostics = diagnostics.copy(fullRefolds = diagnostics.fullRefolds + 1)
            val state = GroupProjector.project(groupId, applicable)
            flowFor(groupId).value = state
            GroupCache(log, state, applicable.size, applicable.lastOrNull()?.id?.value, log.heads, log.maxLamport)
        }
        caches[groupId] = updated
        return updated
    }

    private suspend fun mergeLocked(groupId: GroupId, cache: GroupCache, ops: List<Op>) {
        val oldLog = checkNotNull(cache.log) { "mergeLocked requires a loaded op log" }
        val newLog = oldLog.merge(ops)
        if (newLog === oldLog) return

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

        val lastId = applicable.lastOrNull()?.id?.value
        persistProjection(groupId, newState, applicable.size, lastId, newLog)
        caches[groupId] = GroupCache(newLog, newState, applicable.size, lastId, newLog.heads, newLog.maxLamport)
        flowFor(groupId).value = newState
        noteGroupLocked(groupId)
    }

    private suspend fun snapshotEntity(
        groupId: GroupId,
        state: GroupState,
        appliedCount: Int,
        lastAppliedOpId: String?,
        log: OpLog,
    ) = StateSnapshotEntity(
        groupId = groupId.value,
        codecVersion = GroupStateCodec.VERSION,
        opRowCount = db.opDao().countForGroup(groupId.value),
        appliedOpCount = appliedCount,
        lastAppliedOpId = lastAppliedOpId,
        maxLamport = log.maxLamport,
        headsJson = json.encodeToString(log.heads.map { it.value }.sorted()),
        stateJson = GroupStateCodec.encode(state),
    )

    @Suppress("LongParameterList") // one row's worth of checkpoint fields
    private suspend fun persistProjection(
        groupId: GroupId,
        state: GroupState,
        appliedCount: Int,
        lastAppliedOpId: String?,
        log: OpLog,
    ) {
        diagnostics = diagnostics.copy(projectionWrites = diagnostics.projectionWrites + 1)
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
            snapshot = snapshotEntity(groupId, state, appliedCount, lastAppliedOpId, log),
        )
    }
}
