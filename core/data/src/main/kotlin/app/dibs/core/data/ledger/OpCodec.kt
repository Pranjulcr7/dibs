// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.ledger

import app.dibs.core.data.db.OpEntity
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.Op
import app.dibs.core.domain.ledger.OpId
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import kotlinx.serialization.json.Json

/**
 * Maps ops to and from their Room rows. The JSON here is a *storage* format
 * only — op identity always comes from the domain's canonical encoding, which
 * is why [fromEntity] trusts the stored `opId` column but callers who need
 * tamper-evidence can recompute it via `OpFactory.computeId`.
 */
object OpCodec {

    /** Tolerant of unknown keys so a newer app version's rows don't brick an older reader outright. */
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun toEntity(op: Op): OpEntity = OpEntity(
        opId = op.id.value,
        groupId = op.groupId.value,
        lamport = op.lamport,
        deviceId = op.deviceId.value,
        parentsJson = json.encodeToString(op.parents.map { it.value }.sorted()),
        wallClockMillis = op.wallClockMillis,
        payloadJson = json.encodeToString<OpPayload>(op.payload),
    )

    fun fromEntity(entity: OpEntity): Op = Op(
        id = OpId(entity.opId),
        groupId = GroupId(entity.groupId),
        lamport = entity.lamport,
        deviceId = DeviceId(entity.deviceId),
        parents = json.decodeFromString<List<String>>(entity.parentsJson).map(::OpId).toSet(),
        wallClockMillis = entity.wallClockMillis,
        payload = json.decodeFromString<OpPayload>(entity.payloadJson),
    )
}
