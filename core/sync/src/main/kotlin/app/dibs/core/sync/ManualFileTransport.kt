// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.sync

import app.dibs.core.domain.ledger.Op
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.sync.SyncTransport
import app.dibs.core.domain.sync.TransportCapability
import app.dibs.core.domain.sync.VectorClock

/**
 * Tier 1 (FR-Y1, D41): sync where the *user* is the network. Bundles move
 * through the share sheet and the import flow — so [push] succeeds without
 * transmitting anything (there is no channel to transmit on; the UI reads
 * [capability] and offers "Share updates" instead of expecting delivery), and
 * [pull] never has anything (arrivals come through bundle import). The
 * interface is still worth implementing faithfully: the day a relay ships
 * (M8), only the binding changes.
 */
class ManualFileTransport : SyncTransport {

    override val capability: TransportCapability = TransportCapability.MANUAL

    override suspend fun push(groupId: GroupId, ops: List<Op>): Result<Unit> = Result.success(Unit)

    override suspend fun pull(groupId: GroupId, since: VectorClock): Result<List<Op>> = Result.success(emptyList())
}
