// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.GroupLedger
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.Op
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.settings.SettingsStore
import app.dibs.core.domain.settings.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * In-memory [Ledger] over the real domain [GroupLedger], so ViewModel tests
 * exercise the true fold semantics rather than a mock's guesses.
 */
class FakeLedger(
    private val deviceId: DeviceId = DeviceId("fa000000-0000-4000-8000-000000000000"),
) : Ledger {

    private val ledgers = LinkedHashMap<GroupId, GroupLedger>()
    private val stateFlows = LinkedHashMap<GroupId, MutableStateFlow<GroupState?>>()
    private val groupIds = MutableStateFlow<Set<GroupId>>(emptySet())
    private var clock = 1_000_000L

    private fun ledgerFor(groupId: GroupId): GroupLedger =
        ledgers.getOrPut(groupId) { GroupLedger(groupId, deviceId) }

    private fun flowFor(groupId: GroupId): MutableStateFlow<GroupState?> =
        stateFlows.getOrPut(groupId) { MutableStateFlow(ledgers[groupId]?.state()) }

    private fun publish(groupId: GroupId) {
        flowFor(groupId).value = ledgerFor(groupId).state()
        groupIds.value = groupIds.value + groupId
    }

    override fun observeGroupIds(): Flow<Set<GroupId>> = groupIds

    override fun observeState(groupId: GroupId): Flow<GroupState> {
        if (flowFor(groupId).value == null) flowFor(groupId).value = ledgerFor(groupId).state()
        return flowFor(groupId).filterNotNull()
    }

    override suspend fun state(groupId: GroupId): GroupState = ledgerFor(groupId).state()

    override suspend fun append(groupId: GroupId, payload: OpPayload): Op {
        clock += 60_000
        val op = ledgerFor(groupId).append(payload, clock)
        publish(groupId)
        return op
    }

    override suspend fun ingest(groupId: GroupId, ops: Collection<Op>) {
        ledgerFor(groupId).receive(ops)
        publish(groupId)
    }

    override suspend fun pendingCount(groupId: GroupId): Int = ledgerFor(groupId).log.pending.size

    override suspend fun totalOpCount(): Long = ledgers.values.sumOf { it.log.all.size.toLong() }

    override suspend fun allOps(groupId: GroupId): List<Op> = ledgerFor(groupId).log.all.toList()
}

/** In-memory settings store mirroring the DataStore semantics. */
class FakeSettingsStore(initial: UserSettings = UserSettings()) : SettingsStore {
    val state = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = state
    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        state.value = transform(state.value)
    }
}
