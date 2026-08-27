// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.sync

import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.Op

/**
 * Per-device high-water marks over an op set: for each device, the largest
 * lamport it has authored that we have seen.
 *
 * Why this exists (SPEC edge 75, FR-Y4): a sync bundle carries the *sender's*
 * clock. After ingesting the bundle, comparing that clock against our own is
 * how "some changes may be missing" becomes a computable fact instead of a
 * guess — the sender attested to knowing ops we still don't have. No wall
 * clocks, no server arbitration, deterministic on both ends (invariant 6).
 *
 * A clock is a summary, not a proof: it cannot name *which* ops are missing,
 * only which devices are ahead and by how much. That is exactly enough for
 * the UI's honesty requirement ("never a silent failure").
 */
data class VectorClock(val perDevice: Map<DeviceId, Long>) {

    /** True when this clock has seen everything [other] has — no gap. */
    fun covers(other: VectorClock): Boolean =
        other.perDevice.all { (device, lamport) -> (perDevice[device] ?: 0L) >= lamport }

    /** Pointwise maximum — the clock after learning everything both sides know. */
    fun merge(other: VectorClock): VectorClock {
        val merged = HashMap(perDevice)
        other.perDevice.forEach { (device, lamport) -> merged.merge(device, lamport) { a, b -> maxOf(a, b) } }
        return VectorClock(merged)
    }

    /**
     * Devices where [other] is ahead of us, with how far behind we are —
     * empty when [covers] holds. Feeds the "changes may be missing" notice.
     */
    fun behind(other: VectorClock): Map<DeviceId, Long> = buildMap {
        other.perDevice.forEach { (device, lamport) ->
            val ours = this@VectorClock.perDevice[device] ?: 0L
            if (lamport > ours) put(device, lamport - ours)
        }
    }

    companion object {
        val EMPTY = VectorClock(emptyMap())

        /** The clock of an op collection — applied and pending alike, since both are "seen". */
        fun of(ops: Collection<Op>): VectorClock {
            val marks = HashMap<DeviceId, Long>()
            ops.forEach { op -> marks.merge(op.deviceId, op.lamport) { a, b -> maxOf(a, b) } }
            return VectorClock(marks)
        }
    }
}
