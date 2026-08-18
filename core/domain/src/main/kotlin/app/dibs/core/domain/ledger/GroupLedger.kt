// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.GroupId

/**
 * One device's view of one group's ledger: an [OpLog] plus the bookkeeping for
 * appending local ops — the next lamport (max seen + 1) and the causal parents
 * (the current heads).
 *
 * Why this lives in the domain and not the data layer: the convergence
 * guarantees only hold if *every* writer — the app, the sync engine, and the
 * test simulation — appends through the same rules. This class is those rules.
 */
class GroupLedger(
    val groupId: GroupId,
    val deviceId: DeviceId,
    initial: OpLog = OpLog.empty(),
) {

    var log: OpLog = initial
        private set

    private var cachedForLog: OpLog? = null
    private var cachedState: GroupState? = null

    /** Creates, assigns causality to, and merges a local op; returns it for replication. */
    fun append(payload: OpPayload, wallClockMillis: Long): Op {
        val op = OpFactory.create(
            groupId = groupId,
            deviceId = deviceId,
            lamport = log.maxLamport + 1,
            parents = log.heads,
            wallClockMillis = wallClockMillis,
            payload = payload,
        )
        log = log.merge(listOf(op))
        return op
    }

    /** Merges remote ops; ops for other groups are ignored, duplicates are no-ops. */
    fun receive(ops: Collection<Op>) {
        log = log.merge(ops.filter { it.groupId == groupId })
    }

    /** The folded state; memoized on log identity because [OpLog] is immutable. */
    fun state(): GroupState {
        val current = log
        if (cachedForLog !== current) {
            cachedState = current.project(groupId)
            cachedForLog = current
        }
        return checkNotNull(cachedState)
    }
}
