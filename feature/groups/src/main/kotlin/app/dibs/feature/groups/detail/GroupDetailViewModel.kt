// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.ledger.EmojiEdit
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupDetailUiState(
    val state: GroupState? = null,
    val selfMember: MemberId? = null,
    val pendingCount: Int = 0,
) {
    /** My net in the group's default currency; null when unknown. */
    val myNet: Money?
        get() {
            val currency = state?.meta?.defaultCurrency ?: return null
            val self = selfMember ?: return null
            return state?.balances()?.get(currency)?.firstOrNull { it.memberId == self }?.net ?: Money.ZERO
        }
}

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledger: Ledger,
    settingsStore: SettingsStore,
) : ViewModel() {

    private val groupId = GroupId(checkNotNull(savedStateHandle["groupId"]) { "groupId nav argument missing" })

    val uiState: StateFlow<GroupDetailUiState> =
        combine(ledger.observeState(groupId), settingsStore.settings) { state, settings ->
            GroupDetailUiState(
                state = state,
                selfMember = settings.selfMembers[groupId],
                pendingCount = ledger.pendingCount(groupId),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), GroupDetailUiState())

    /**
     * Params are the *desired* values from the edit dialog (blank emoji =
     * cleared; null/blank name = unchanged). Only actual changes are appended;
     * a no-op edit writes nothing to the ledger.
     */
    fun editGroup(name: String?, emoji: String?, defaultCurrency: CurrencyCode?) {
        val meta = uiState.value.state?.meta ?: return
        val payload = OpPayload.EditGroup(
            name = name?.trim()?.takeUnless { it.isEmpty() || it == meta.name },
            emoji = emoji?.takeUnless { it.isBlank() }.let { new ->
                if (new == meta.emoji) null else EmojiEdit(new)
            },
            defaultCurrency = defaultCurrency?.takeUnless { it == meta.defaultCurrency },
        )
        if (payload == OpPayload.EditGroup()) return
        viewModelScope.launch { ledger.append(groupId, payload) }
    }

    /** FR-G4: archive is shared, reversible state; the group stays readable. */
    fun setArchived(archived: Boolean) {
        viewModelScope.launch { ledger.append(groupId, OpPayload.SetArchived(archived)) }
    }

    /** FR-S3/S4: settlements are their own transaction type; partial amounts welcome. */
    fun recordSettlement(from: MemberId, to: MemberId, amount: Money, currency: CurrencyCode, kind: String? = null) {
        if (!amount.isPositive || from == to) return
        viewModelScope.launch {
            ledger.append(
                groupId,
                OpPayload.RecordSettlement(
                    settlementId = SettlementId(java.util.UUID.randomUUID().toString()),
                    from = from,
                    to = to,
                    amount = amount,
                    currency = currency,
                    kind = kind,
                ),
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
