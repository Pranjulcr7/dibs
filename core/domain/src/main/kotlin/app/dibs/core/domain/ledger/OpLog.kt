// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.GroupId

/**
 * An immutable set of ops keyed by content-addressed id.
 *
 * Why a set and nothing more: merge is set union, so replication needs no
 * server, no conflict resolution protocol, and no ordering guarantees from the
 * transport — any channel that eventually delivers ops works (SPEC §5.5).
 * Duplicates are no-ops by construction (case 23).
 */
class OpLog private constructor(
    private val opsById: Map<OpId, Op>,
) {

    val size: Int get() = opsById.size
    val all: Collection<Op> get() = opsById.values

    fun contains(id: OpId): Boolean = opsById.containsKey(id)

    /** Highest lamport seen, including pending ops — the next local op must sort after everything known. */
    val maxLamport: Long by lazy { opsById.values.maxOfOrNull { it.lamport } ?: 0L }

    /** Newest wall clock across all ops, for the display-only skew warning (case 22). */
    val newestWallClockMillis: Long? by lazy { opsById.values.maxOfOrNull { it.wallClockMillis } }

    /**
     * Ids of ops whose whole ancestry is present. Ops outside this set are
     * buffered, not folded — showing half a causal chain would surface as
     * missing data instead of "syncing" (case 24).
     */
    private val completeIds: Set<OpId> by lazy {
        val complete = HashSet<OpId>(opsById.size)
        var grew = true
        while (grew) {
            grew = false
            opsById.values.forEach { op ->
                if (op.id !in complete && op.parents.all { it in complete }) {
                    complete.add(op.id)
                    grew = true
                }
            }
        }
        complete
    }

    /**
     * The causally-complete ops in replay order `(lamport, deviceId, opId)` —
     * never wall clock, never arrival order (CLAUDE.md invariant 6).
     */
    val applicable: List<Op> by lazy {
        opsById.values.filter { it.id in completeIds }.sortedWith(TOTAL_ORDER)
    }

    /** Ops waiting for missing ancestors; surfaced to the UI as "syncing". */
    val pending: Set<Op> by lazy {
        opsById.values.filterNot { it.id in completeIds }.toSet()
    }

    /**
     * The frontier: applicable ops no other applicable op names as a parent.
     * These become the parents of the next local op, encoding causality without
     * any clock.
     */
    val heads: Set<OpId> by lazy {
        val referenced = HashSet<OpId>()
        applicable.forEach { referenced.addAll(it.parents) }
        applicable.asSequence().map { it.id }.filterNot { it in referenced }.toSet()
    }

    /** Set union; returns `this` unchanged when nothing new arrived. */
    fun merge(ops: Collection<Op>): OpLog {
        val incoming = ops.filterNot { opsById.containsKey(it.id) }
        if (incoming.isEmpty()) return this
        val merged = HashMap(opsById)
        incoming.forEach { merged[it.id] = it }
        return OpLog(merged)
    }

    /** Folds the applicable ops of one group into state; pure and deterministic. */
    fun project(groupId: GroupId): GroupState =
        GroupProjector.project(groupId, applicable.filter { it.groupId == groupId })

    companion object {
        fun empty(): OpLog = OpLog(emptyMap())

        /** The single replay order every device agrees on (SPEC §5.5). */
        val TOTAL_ORDER: Comparator<Op> =
            compareBy({ it.lamport }, { it.deviceId }, { it.id })
    }
}
