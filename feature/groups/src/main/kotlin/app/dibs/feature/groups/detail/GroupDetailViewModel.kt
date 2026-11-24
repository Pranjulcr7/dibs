// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.importer.CsvExport
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
import app.dibs.core.domain.sync.BundleSync
import app.dibs.core.domain.sync.SyncMetaStore
import app.dibs.core.domain.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupDetailUiState(
    val state: GroupState? = null,
    val selfMember: MemberId? = null,
    val pendingCount: Int = 0,
    val syncStatus: SyncStatus? = null,
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
    private val bundleSync: BundleSync,
    private val syncMeta: SyncMetaStore,
) : ViewModel() {

    private val groupId = GroupId(checkNotNull(savedStateHandle["groupId"]) { "groupId nav argument missing" })

    /** Bumped after share/import so the FR-Y4 chip refreshes without a ledger change. */
    private val syncTick = MutableStateFlow(0)

    private val _syncEvents = MutableSharedFlow<BundleSync.ImportOutcome>(extraBufferCapacity = 1)
    val syncEvents: SharedFlow<BundleSync.ImportOutcome> = _syncEvents.asSharedFlow()

    val uiState: StateFlow<GroupDetailUiState> =
        combine(ledger.observeState(groupId), settingsStore.settings, syncTick) { state, settings, _ ->
            GroupDetailUiState(
                state = state,
                selfMember = settings.selfMembers[groupId],
                pendingCount = ledger.pendingCount(groupId),
                syncStatus = SyncStatus.of(syncMeta.metaFor(groupId), ledger.opCount(groupId)),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), GroupDetailUiState())

    /** Seals the whole group for the share sheet (FR-Y1); the screen owns the intent. */
    suspend fun exportSyncBundle(): ByteArray = bundleSync.exportBundle(groupId).also { syncTick.value += 1 }

    /** Applies a received bundle; every outcome surfaces as a specific message (FR-Y4). */
    fun importSyncBundle(bytes: ByteArray) {
        viewModelScope.launch {
            _syncEvents.emit(bundleSync.importBundle(bytes))
            syncTick.value += 1
        }
    }

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

    /** FR-I2: the group's ledger as a Splitwise-shaped CSV (itself re-importable). */
    suspend fun exportCsv(): String = CsvExport.export(ledger.state(groupId))

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
