// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.GroupId
import kotlinx.serialization.Serializable

/**
 * Content-addressed id of an [Op]: the BLAKE3 hash (hex) of the op's canonical
 * byte encoding. Why content addressing: merge becomes set union and duplicate
 * delivery a no-op for free (SPEC §5.5, §6.3 case 23).
 */
@Serializable
@JvmInline
value class OpId(val value: String) : Comparable<OpId> {
    init {
        require(value.isNotBlank()) { "OpId must not be blank" }
    }

    override fun compareTo(other: OpId): Int = value.compareTo(other.value)
}

/**
 * Stable identifier of an installed device. Part of the replay total order
 * `(lamport, deviceId, opId)`, which is why it is `Comparable` — insertion
 * order never decides anything (CLAUDE.md invariant 6).
 */
@Serializable
@JvmInline
value class DeviceId(val value: String) : Comparable<DeviceId> {
    init {
        require(value.isNotBlank()) { "DeviceId must not be blank" }
    }

    override fun compareTo(other: DeviceId): Int = value.compareTo(other.value)
}

/**
 * One immutable entry in the append-only ledger (SPEC §5.5).
 *
 * [lamport] plus [deviceId] plus [id] give the deterministic replay order.
 * [wallClockMillis] exists for *display only* — device clocks are wrong and
 * users travel (SPEC §6.3 case 22); nothing may ever order by it.
 * [parents] are the causal predecessors (the creator's heads at append time);
 * a receiver buffers an op until its ancestry is complete (case 24).
 */
data class Op(
    val id: OpId,
    val groupId: GroupId,
    val lamport: Long,
    val deviceId: DeviceId,
    val parents: Set<OpId>,
    val wallClockMillis: Long,
    val payload: OpPayload,
)
