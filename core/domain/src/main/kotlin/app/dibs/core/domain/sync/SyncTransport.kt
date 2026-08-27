// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.sync

import app.dibs.core.domain.ledger.Op
import app.dibs.core.domain.model.GroupId

/**
 * What a transport can promise, read by the UI to decide which sync
 * affordances to show (SPEC §5.6). The UI never branches on a concrete
 * transport class — capability is the entire contract surface.
 */
enum class TransportCapability {
    /** Bundles move only when the user explicitly shares or imports one. */
    MANUAL,

    /** A relay tries in the background; delivery is not guaranteed or timely. */
    BEST_EFFORT,

    /** A connected channel delivers promptly while open. */
    REALTIME,
}

/**
 * The seam every sync tier implements (SPEC §5.6): `ManualFileTransport`
 * ships in v1, a relay may implement `BEST_EFFORT` in M8, and tests use
 * [NoOpTransport]. Nothing behind this interface may block expense entry
 * (FR-Y5) and the app must function fully when [push]/[pull] never succeed —
 * airplane mode is a first-class state (invariant 3).
 */
interface SyncTransport {

    val capability: TransportCapability

    /** Offer local ops to the channel. MANUAL transports succeed without moving anything. */
    suspend fun push(groupId: GroupId, ops: List<Op>): Result<Unit>

    /** Ask the channel for ops beyond [since]. MANUAL transports always return empty. */
    suspend fun pull(groupId: GroupId, since: VectorClock): Result<List<Op>>
}

/** The tests' transport, and the proof the app runs with sync entirely absent. */
object NoOpTransport : SyncTransport {
    override val capability: TransportCapability = TransportCapability.MANUAL

    override suspend fun push(groupId: GroupId, ops: List<Op>): Result<Unit> = Result.success(Unit)

    override suspend fun pull(groupId: GroupId, since: VectorClock): Result<List<Op>> = Result.success(emptyList())
}
