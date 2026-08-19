// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.expense.editor

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

/** In-memory [Ledger] over the real domain fold (same as the groups module's). */
class FakeLedger(
    private val deviceId: DeviceId = DeviceId("fa000000-0000-4000-8000-000000000000"),
) : Ledger {

    private val ledgers = LinkedHashMap<GroupId, GroupLedger>()
    private val stateFlows = LinkedHashMap<GroupId, MutableStateFlow<GroupState?>>()
    private val groupIds = MutableStateFlow<Set<GroupId>>(emptySet())
    private var clock = 1_000_000L

    private fun ledgerFor(groupId: GroupId) = ledgers.getOrPut(groupId) { GroupLedger(groupId, deviceId) }

    private fun flowFor(groupId: GroupId) = stateFlows.getOrPut(groupId) { MutableStateFlow(null) }

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
}

class FakeSettingsStore(initial: UserSettings = UserSettings()) : SettingsStore {
    val state = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = state
    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        state.value = transform(state.value)
    }
}
