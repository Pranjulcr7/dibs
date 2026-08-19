// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One card in the group list (SPEC §4.1 screen 1). */
data class GroupCardUi(
    val id: GroupId,
    val name: String,
    val emoji: String?,
    val memberCount: Int,
    /** Positive = you are owed, negative = you owe, zero = settled; null when this device has no self member. */
    val myNet: Money?,
    val currency: CurrencyCode?,
)

sealed interface GroupsUiState {
    data object Loading : GroupsUiState

    data class Content(val groups: List<GroupCardUi>) : GroupsUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val ledger: Ledger,
    settingsStore: SettingsStore,
) : ViewModel() {

    private val states = ledger.observeGroupIds().flatMapLatest { ids ->
        if (ids.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(ids.sorted().map { id -> ledger.observeState(id) }) { it.toList() }
        }
    }

    val uiState: StateFlow<GroupsUiState> =
        combine(states, settingsStore.settings) { groupStates, settings ->
            GroupsUiState.Content(
                groupStates
                    .map { state -> state.toCard(settings.selfMembers[state.groupId]) }
                    .sortedBy { it.name.lowercase() },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), GroupsUiState.Loading)

    private fun GroupState.toCard(selfMember: app.dibs.core.domain.model.MemberId?): GroupCardUi {
        // The card shows the group's default currency; cross-currency positions
        // are never summed (SPEC §6.5 case 53) and get their detail in-group.
        val currency = meta?.defaultCurrency ?: balances().keys.firstOrNull()
        val net = if (selfMember == null || currency == null) {
            null
        } else {
            balances()[currency]?.firstOrNull { it.memberId == selfMember }?.net ?: Money.ZERO
        }
        return GroupCardUi(
            id = groupId,
            name = meta?.name.orEmpty(),
            emoji = meta?.emoji,
            memberCount = members.values.count { !it.removed },
            myNet = net,
            currency = currency,
        )
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
