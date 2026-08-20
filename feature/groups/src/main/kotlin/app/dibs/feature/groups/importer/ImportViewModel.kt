// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.crypto.IdentitySource
import app.dibs.core.domain.importer.SplitwiseCsv
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.ledger.SignedPayloads
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * FR-I1: Splitwise import — parse, map names, preview (with skipped rows and
 * the checksum verdict), and only then write. The import lands in a **new**
 * group; every expense id derives from the file+row hash, and a file whose
 * rows already exist anywhere is refused, so re-importing cannot duplicate
 * (edge 55). Import is all-or-nothing per confirm (edge 65's transactional
 * spirit: ops append in one pass; a crash mid-way re-imports idempotently).
 */
sealed interface ImportStep {
    data object PickFile : ImportStep

    data class BadHeader(val reason: Int) : ImportStep

    data class Mapping(
        val parsed: SplitwiseCsv.Result.Success,
        val names: Map<String, String>,
        val selfName: String?,
    ) : ImportStep

    data class Preview(
        val parsed: SplitwiseCsv.Result.Success,
        val names: Map<String, String>,
        val selfName: String?,
        val groupName: String,
        val alreadyImported: Boolean,
    ) : ImportStep

    /** The write is running; confirm is not re-enterable (a 5k import takes real time). */
    data object Importing : ImportStep
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val ledger: Ledger,
    private val settingsStore: SettingsStore,
    private val identitySource: IdentitySource,
) : ViewModel() {

    private val _step = MutableStateFlow<ImportStep>(ImportStep.PickFile)
    val step: StateFlow<ImportStep> = _step.asStateFlow()

    private val _done = MutableSharedFlow<GroupId>(replay = 1)
    val done: SharedFlow<GroupId> = _done.asSharedFlow()

    fun onFileLoaded(text: String) {
        when (val result = SplitwiseCsv.parse(text)) {
            is SplitwiseCsv.Result.UnrecognizedHeader ->
                _step.value = ImportStep.BadHeader(0)
            is SplitwiseCsv.Result.Success ->
                _step.value = ImportStep.Mapping(
                    parsed = result,
                    names = result.memberNames.associateWith { it },
                    selfName = null,
                )
        }
    }

    fun renameMember(original: String, newName: String) {
        _step.update { step ->
            if (step is ImportStep.Mapping) step.copy(names = step.names + (original to newName)) else step
        }
    }

    fun chooseSelf(original: String?) {
        _step.update { step ->
            if (step is ImportStep.Mapping) step.copy(selfName = original) else step
        }
    }

    fun toPreview(groupName: String) {
        val mapping = _step.value as? ImportStep.Mapping ?: return
        viewModelScope.launch {
            val firstId = mapping.parsed.rows
                .filterIsInstance<SplitwiseCsv.Row.Expense>()
                .firstOrNull()?.stableId
            val alreadyImported = firstId != null && ledgerContainsExpense(firstId)
            _step.value = ImportStep.Preview(
                parsed = mapping.parsed,
                names = mapping.names,
                selfName = mapping.selfName,
                groupName = groupName.trim().ifEmpty { DEFAULT_GROUP_NAME },
                alreadyImported = alreadyImported,
            )
        }
    }

    fun backToMapping() {
        val preview = _step.value as? ImportStep.Preview ?: return
        _step.value = ImportStep.Mapping(preview.parsed, preview.names, preview.selfName)
    }

    fun confirm() {
        val preview = _step.value as? ImportStep.Preview ?: return
        if (preview.alreadyImported) return
        // Stepping to Importing before launching is the double-tap guard: a
        // second confirm() finds no Preview and returns.
        _step.value = ImportStep.Importing
        viewModelScope.launch {
            val groupId = GroupId(UUID.randomUUID().toString())
            val currency = preview.parsed.currencyTotalsMinor.keys.firstOrNull() ?: "USD"
            val memberIds = preview.parsed.memberNames.associateWith { MemberId(UUID.randomUUID().toString()) }
            val selfId = preview.selfName?.let(memberIds::getValue)

            // One batched append: a per-op loop rewrites the projection per op,
            // which is O(n²) and took minutes for 5,000 rows on device (T7).
            val payloads = buildList {
                add(OpPayload.CreateGroup(preview.groupName, defaultCurrency = CurrencyCode(currency)))
                preview.parsed.memberNames.forEach { original ->
                    add(OpPayload.AddMember(memberIds.getValue(original), preview.names[original] ?: original))
                }
                if (selfId != null) {
                    add(SignedPayloads.claimMember(groupId, selfId, identitySource.keyPair()))
                }
                preview.parsed.rows.forEach { row -> add(row.toPayload(memberIds)) }
            }
            ledger.appendAll(groupId, payloads)
            if (selfId != null) {
                settingsStore.update { it.copy(selfMembers = it.selfMembers + (groupId to selfId)) }
            }
            _done.emit(groupId)
        }
    }

    private fun SplitwiseCsv.Row.toPayload(memberIds: Map<String, MemberId>): OpPayload = when (this) {
        is SplitwiseCsv.Row.Expense -> OpPayload.AddExpense(
            ExpenseId(stableId),
            ExpenseFields(
                description = description,
                currency = CurrencyCode(currency),
                total = Money(totalMinor),
                paidBy = paidByMinor.entries.associate { (name, minor) ->
                    memberIds.getValue(name) to Money(minor)
                },
                owedBy = owedByMinor.entries.associate { (name, minor) ->
                    memberIds.getValue(name) to Money(minor)
                },
                dateEpochDay = dateEpochDay,
                category = category?.lowercase(),
            ),
        )
        is SplitwiseCsv.Row.Settlement -> OpPayload.RecordSettlement(
            settlementId = SettlementId(stableId),
            from = memberIds.getValue(fromName),
            to = memberIds.getValue(toName),
            amount = Money(amountMinor),
            currency = CurrencyCode(currency),
        )
    }

    private suspend fun ledgerContainsExpense(stableId: String): Boolean {
        val groups = ledger.observeGroupIds().first()
        return groups.any { groupId ->
            ledger.state(groupId).expenses.containsKey(ExpenseId(stableId))
        }
    }

    private companion object {
        const val DEFAULT_GROUP_NAME = "Splitwise import"
    }
}
