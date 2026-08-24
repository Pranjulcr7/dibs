// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.expense.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.ledger.AmountsEdit
import app.dibs.core.domain.ledger.CategoryEdit
import app.dibs.core.domain.ledger.ExpenseEdit
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.ExpenseState
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.NoteEdit
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.money.MoneyInput
import app.dibs.core.domain.receipt.ReceiptHandoff
import app.dibs.core.domain.settings.SettingsStore
import app.dibs.core.domain.split.Allocation
import app.dibs.core.domain.split.LineItem
import app.dibs.core.domain.split.SplitCalculator
import app.dibs.core.domain.split.SplitResult
import app.dibs.core.domain.split.SplitSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** The six modes as UI tags; also stored in `ExpenseFields.splitMode` (D32). */
enum class SplitModeUi(val tag: String) {
    EQUAL("equal"),
    EXACT("exact"),
    PERCENT("percent"),
    SHARES("shares"),
    ADJUSTMENT("adjustment"),
    ITEMIZED("itemized"),
    ;

    companion object {
        fun fromTag(tag: String?): SplitModeUi = entries.firstOrNull { it.tag == tag } ?: EQUAL
    }
}

/**
 * Everything the user typed, kept in [SavedStateHandle] as JSON so a draft
 * survives process death (SPEC §6.6 case 61). Ids are raw strings here purely
 * for serialization simplicity.
 */
@Serializable
data class EditorDraft(
    val amountText: String = "",
    val description: String = "",
    val note: String = "",
    val category: String? = null,
    val dateEpochDay: Long = 0,
    val currency: String? = null,
    val payer: String? = null,
    val mode: String = "equal",
    val participants: Set<String> = emptySet(),
    val exactText: Map<String, String> = emptyMap(),
    val percentText: Map<String, String> = emptyMap(),
    val shares: Map<String, Long> = emptyMap(),
    val adjustmentText: Map<String, String> = emptyMap(),
    val items: List<ItemDraft> = emptyList(),
    val taxText: String = "",
    val tipText: String = "",
    /** FR-E7: reminder period in days (7, 30) — device-local rule, not part of the op. */
    val repeatDays: Int? = null,
    /** Set when this draft came from a scanned receipt (FR-E8); stored on save. */
    val receiptRef: String? = null,
)

@Serializable
data class ItemDraft(
    val id: String,
    val name: String = "",
    val priceText: String = "",
    val consumers: Map<String, Long> = emptyMap(),
)

/**
 * The live "remaining to assign" state (SPEC §4.1 screen 4). Every variant is
 * derived from a real [SplitCalculator.split] run — the ViewModel never
 * re-implements allocation math, so the indicator can never disagree with what
 * save would write (SPEC §5.2).
 */
sealed interface SplitPreview {
    /** Valid: allocations sum exactly; [absorbers] got the leftover cents ("Alice +$0.01"). */
    data class Ready(
        val allocations: List<Allocation>,
        val absorbers: List<Allocation>,
        val total: Money,
    ) : SplitPreview

    data class RemainingMoney(val remaining: Money) : SplitPreview

    data class RemainingPercent(val shortfallBasisPoints: Long) : SplitPreview

    enum class Issue {
        NO_AMOUNT,
        NO_PARTICIPANTS,
        ALL_SHARES_ZERO,
        ADJUSTMENTS_EXCEED,
        NEGATIVE_SHARE,
        ITEM_UNASSIGNED,
        INVALID_INPUT,
        OVERFLOW,
    }

    data class NotReady(val issue: Issue) : SplitPreview
}

data class EditorMember(val id: MemberId, val name: String, val isSelf: Boolean)

data class EditorUiState(
    val draft: EditorDraft = EditorDraft(),
    val members: List<EditorMember> = emptyList(),
    val currency: CurrencyCode? = null,
    val isEdit: Boolean = false,
    val history: List<app.dibs.core.domain.ledger.ExpenseEvent> = emptyList(),
    val loaded: Boolean = false,
) {
    val mode: SplitModeUi get() = SplitModeUi.fromTag(draft.mode)

    val preview: SplitPreview
        get() {
            val ccy = currency ?: return SplitPreview.NotReady(SplitPreview.Issue.NO_AMOUNT)
            return previewFor(draft, ccy)
        }

    val itemizedTotal: Money?
        get() = currency?.let { itemizedTotalOf(draft, it) }

    val canSave: Boolean
        get() = loaded && draft.description.isNotBlank() && draft.payer != null && preview is SplitPreview.Ready

    /** True exactly when a blank description is the only thing keeping Save disabled. */
    val blockedOnDescription: Boolean
        get() = loaded && !canSave && draft.description.isBlank() && preview is SplitPreview.Ready
}

@HiltViewModel
class ExpenseEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val ledger: Ledger,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val groupId = GroupId(checkNotNull(savedStateHandle["groupId"]) { "groupId nav argument missing" })
    private val expenseId: ExpenseId? = savedStateHandle.get<String>("expenseId")?.let(::ExpenseId)
    private val duplicateOf: ExpenseId? = savedStateHandle.get<String>("duplicateOf")?.let(::ExpenseId)
    private val receiptHandoff: ReceiptHandoff? =
        savedStateHandle.get<String>("receiptDraft")?.let(ReceiptHandoff::decode)

    private val json = Json
    private val draftFlow = MutableStateFlow(EditorDraft())
    private var original: ExpenseState? = null
    private var initialized = false
    private val loadedFlow = MutableStateFlow(false)

    private val _saved = MutableSharedFlow<Unit>(replay = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    val uiState: StateFlow<EditorUiState> =
        combine(
            draftFlow,
            ledger.observeState(groupId),
            settingsStore.settings,
            loadedFlow,
        ) { draft, state, settings, loaded ->
            EditorUiState(
                draft = draft,
                members = state.members.values
                    .filter { it.active && !it.removed }
                    .map { EditorMember(it.id, it.name, settings.selfMembers[groupId] == it.id) }
                    .sortedWith(compareByDescending<EditorMember> { it.isSelf }.thenBy { it.name.lowercase() }),
                currency = draft.currency?.let(::CurrencyCode) ?: state.meta?.defaultCurrency ?: CurrencyCode("USD"),
                isEdit = expenseId != null,
                history = expenseId?.let { state.expenses[it]?.history } ?: emptyList(),
                loaded = loaded,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), EditorUiState())

    init {
        viewModelScope.launch { initialize(settingsStore) }
    }

    private suspend fun initialize(settingsStore: SettingsStore) {
        if (initialized) return
        initialized = true
        val restored = savedStateHandle.get<String>(DRAFT_KEY)?.let {
            runCatchingSerialization { json.decodeFromString<EditorDraft>(it) }
        }
        val state = ledger.state(groupId)
        original = expenseId?.let { state.expenses[it] }
        val draft = when {
            restored != null -> restored
            original != null -> draftFrom(checkNotNull(original).fields)
            duplicateOf != null && state.expenses[duplicateOf] != null ->
                draftFrom(checkNotNull(state.expenses[duplicateOf]).fields)
            receiptHandoff != null -> draftFromReceipt(receiptHandoff, state, settingsStore)
            else -> {
                val settings = settingsStore.settings.first()
                val self = settings.selfMembers[groupId]
                EditorDraft(
                    dateEpochDay = LocalDate.now().toEpochDay(),
                    payer = self?.value,
                    participants = state.members.values
                        .filter { it.active && !it.removed }
                        .map { it.id.value }
                        .toSet(),
                )
            }
        }
        updateDraft { draft }
        loadedFlow.value = true
    }

    /**
     * The reviewed scan lands as an itemized draft; assignment happens with
     * the same UI as manual entry (FR-R5/R8).
     */
    private suspend fun draftFromReceipt(
        handoff: ReceiptHandoff,
        state: app.dibs.core.domain.ledger.GroupState,
        settingsStore: SettingsStore,
    ): EditorDraft {
        val settings = settingsStore.settings.first()
        val currency = state.meta?.defaultCurrency ?: CurrencyCode("USD")
        return EditorDraft(
            dateEpochDay = LocalDate.now().toEpochDay(),
            payer = settings.selfMembers[groupId]?.value,
            participants = state.members.values
                .filter { it.active && !it.removed }
                .map { it.id.value }
                .toSet(),
            mode = SplitModeUi.ITEMIZED.tag,
            items = handoff.items.map { item ->
                ItemDraft(
                    id = UUID.randomUUID().toString(),
                    name = if (item.quantity > 1) "${item.quantity} x ${item.label}" else item.label,
                    priceText = MoneyInput.toPlainString(Money(item.amountMinor), currency),
                )
            },
            taxText = handoff.taxMinor?.let { MoneyInput.toPlainString(Money(it), currency) }.orEmpty(),
            tipText = handoff.tipMinor?.let { MoneyInput.toPlainString(Money(it), currency) }.orEmpty(),
            receiptRef = handoff.receiptRef,
        )
    }

    fun updateDraft(transform: (EditorDraft) -> EditorDraft) {
        draftFlow.value = transform(draftFlow.value)
        savedStateHandle[DRAFT_KEY] = json.encodeToString(draftFlow.value)
    }

    /**
     * Builds fields via the domain allocator and appends the op; no math
     * happens here. Reads source state directly (not the UI StateFlow, which
     * only materializes while collected).
     */
    fun save() {
        viewModelScope.launch {
            if (!loadedFlow.value) return@launch
            val draft = draftFlow.value
            if (draft.description.isBlank()) return@launch
            val payer = draft.payer?.let(::MemberId) ?: return@launch
            val groupState = ledger.state(groupId)
            val currency = draft.currency?.let(::CurrencyCode)
                ?: groupState.meta?.defaultCurrency
                ?: CurrencyCode("USD")
            val preview = previewFor(draft, currency) as? SplitPreview.Ready ?: return@launch

            val fields = ExpenseFields(
                description = draft.description.trim(),
                currency = currency,
                total = preview.total,
                paidBy = mapOf(payer to preview.total),
                owedBy = preview.allocations.associate { it.memberId to it.amount },
                dateEpochDay = draft.dateEpochDay,
                note = draft.note.trim().ifEmpty { null },
                category = draft.category,
                receiptRef = draft.receiptRef ?: original?.fields?.receiptRef,
                splitMode = SplitModeUi.fromTag(draft.mode).tag,
            )
            val existing = original
            if (existing == null) {
                val newId = ExpenseId(UUID.randomUUID().toString())
                ledger.append(groupId, OpPayload.AddExpense(newId, fields))
                draft.repeatDays?.let { period ->
                    settingsStore.update {
                        it.copy(
                            recurringRules = it.recurringRules + app.dibs.core.domain.settings.RecurringRule(
                                id = UUID.randomUUID().toString(),
                                groupId = groupId,
                                templateExpenseId = newId,
                                periodDays = period,
                                nextDueEpochDay = draft.dateEpochDay + period,
                            ),
                        )
                    }
                }
            } else {
                val edit = diff(existing.fields, fields)
                if (edit != ExpenseEdit()) ledger.append(groupId, OpPayload.EditExpense(existing.id, edit))
            }
            _saved.emit(Unit)
        }
    }

    fun delete() {
        val id = expenseId ?: return
        viewModelScope.launch {
            ledger.append(groupId, OpPayload.DeleteExpense(id))
            _saved.emit(Unit)
        }
    }

    /** Field-level edit op: only what changed is written (case 20's LWW granularity). */
    private fun diff(old: ExpenseFields, new: ExpenseFields): ExpenseEdit {
        val amountsChanged = old.currency != new.currency ||
            old.total != new.total ||
            old.paidBy != new.paidBy ||
            old.owedBy != new.owedBy ||
            old.splitMode != new.splitMode
        return ExpenseEdit(
            description = new.description.takeUnless { it == old.description },
            note = if (new.note != old.note) NoteEdit(new.note) else null,
            dateEpochDay = new.dateEpochDay.takeUnless { it == old.dateEpochDay },
            category = if (new.category != old.category) CategoryEdit(new.category) else null,
            amounts = if (amountsChanged) {
                AmountsEdit(new.currency, new.total, new.paidBy, new.owedBy, new.splitMode)
            } else {
                null
            },
        )
    }

    private fun draftFrom(fields: ExpenseFields): EditorDraft {
        val currency = fields.currency
        // Equal splits reopen as equal; every other mode reopens as exact
        // amounts prefilled with the truth (owedBy) — parameters like
        // percentages are not stored, and guessing them would lie (D32).
        val storedMode = SplitModeUi.fromTag(fields.splitMode)
        val reopenMode = if (storedMode == SplitModeUi.EQUAL) SplitModeUi.EQUAL else SplitModeUi.EXACT
        return EditorDraft(
            amountText = MoneyInput.toPlainString(fields.total, currency),
            description = fields.description,
            note = fields.note.orEmpty(),
            category = fields.category,
            dateEpochDay = fields.dateEpochDay,
            currency = currency.value,
            payer = fields.paidBy.keys.firstOrNull()?.value,
            mode = reopenMode.tag,
            participants = fields.owedBy.keys.map { it.value }.toSet(),
            exactText = fields.owedBy.entries.associate { (member, amount) ->
                member.value to MoneyInput.toPlainString(amount, currency)
            },
        )
    }

    private inline fun <T> runCatchingSerialization(block: () -> T): T? = try {
        block()
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val DRAFT_KEY = "draft"
        const val STOP_TIMEOUT = 5_000L
    }
}

// -- pure preview derivation (unit-testable without the ViewModel) -----------

internal fun itemizedTotalOf(draft: EditorDraft, currency: CurrencyCode): Money? {
    var sum = Money.ZERO
    for (item in draft.items) {
        val price = MoneyInput.parseSigned(item.priceText.ifBlank { "0" }, currency) ?: return null
        sum = try {
            sum + price
        } catch (_: ArithmeticException) {
            return null
        }
    }
    val tax = MoneyInput.parse(draft.taxText.ifBlank { "0" }, currency) ?: return null
    val tip = MoneyInput.parse(draft.tipText.ifBlank { "0" }, currency) ?: return null
    return try {
        sum + tax + tip
    } catch (_: ArithmeticException) {
        null
    }
}

/** Draft -> domain spec -> [SplitCalculator] verdict, mapped for the UI. */
// One early exit per user-visible incomplete state; splitting would separate mode from validation.
@Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
internal fun previewFor(draft: EditorDraft, currency: CurrencyCode): SplitPreview {
    val mode = SplitModeUi.fromTag(draft.mode)
    val participants = draft.participants.map(::MemberId).toSet()

    val total: Money
    val spec: SplitSpec
    if (mode == SplitModeUi.ITEMIZED) {
        if (draft.items.isEmpty()) return SplitPreview.NotReady(SplitPreview.Issue.NO_PARTICIPANTS)
        if (draft.items.any { it.consumers.isEmpty() }) {
            return SplitPreview.NotReady(SplitPreview.Issue.ITEM_UNASSIGNED)
        }
        total = itemizedTotalOf(draft, currency)
            ?: return SplitPreview.NotReady(SplitPreview.Issue.INVALID_INPUT)
        val items = draft.items.map { item ->
            LineItem(
                price = MoneyInput.parseSigned(item.priceText.ifBlank { "0" }, currency)
                    ?: return SplitPreview.NotReady(SplitPreview.Issue.INVALID_INPUT),
                consumers = item.consumers.mapKeys { MemberId(it.key) },
                description = item.name.ifBlank { null },
            )
        }
        spec = SplitSpec.Itemized(
            items = items,
            tax = MoneyInput.parse(draft.taxText.ifBlank { "0" }, currency)
                ?: return SplitPreview.NotReady(SplitPreview.Issue.INVALID_INPUT),
            tip = MoneyInput.parse(draft.tipText.ifBlank { "0" }, currency)
                ?: return SplitPreview.NotReady(SplitPreview.Issue.INVALID_INPUT),
        )
    } else {
        total = MoneyInput.parse(draft.amountText, currency)
            ?: return SplitPreview.NotReady(SplitPreview.Issue.NO_AMOUNT)
        if (participants.isEmpty()) return SplitPreview.NotReady(SplitPreview.Issue.NO_PARTICIPANTS)
        spec = when (mode) {
            SplitModeUi.EQUAL -> SplitSpec.Equal(participants)
            SplitModeUi.EXACT -> SplitSpec.ByExactAmounts(
                participants.associateWith { member ->
                    val text = draft.exactText[member.value].orEmpty()
                    if (text.isBlank()) {
                        Money.ZERO
                    } else {
                        MoneyInput.parse(text, currency)
                            ?: return SplitPreview.NotReady(SplitPreview.Issue.INVALID_INPUT)
                    }
                },
            )
            SplitModeUi.PERCENT -> SplitSpec.ByPercentage(
                participants.associateWith { member ->
                    val text = draft.percentText[member.value].orEmpty()
                    if (text.isBlank()) {
                        0L
                    } else {
                        MoneyInput.parseBasisPoints(text)
                            ?: return SplitPreview.NotReady(SplitPreview.Issue.INVALID_INPUT)
                    }
                },
            )
            SplitModeUi.SHARES -> SplitSpec.ByShares(
                participants.associateWith { draft.shares[it.value] ?: 0L },
            )
            SplitModeUi.ADJUSTMENT -> SplitSpec.ByAdjustment(
                participants = participants,
                adjustments = participants.mapNotNull { member ->
                    val text = draft.adjustmentText[member.value].orEmpty()
                    if (text.isBlank()) {
                        null
                    } else {
                        val delta = MoneyInput.parseSigned(text, currency)
                            ?: return SplitPreview.NotReady(SplitPreview.Issue.INVALID_INPUT)
                        member to delta
                    }
                }.toMap(),
            )
            SplitModeUi.ITEMIZED -> error("handled above")
        }
    }

    return when (val result = SplitCalculator.split(total, spec)) {
        is SplitResult.Success -> SplitPreview.Ready(
            allocations = result.allocations,
            absorbers = result.allocations.filter { it.roundingAdjustment > 0 },
            total = total,
        )
        is SplitResult.Failure.AmountsDoNotSumToTotal -> SplitPreview.RemainingMoney(result.remaining)
        is SplitResult.Failure.PercentagesDoNotSumTo100 ->
            SplitPreview.RemainingPercent(result.shortfallBasisPoints)
        is SplitResult.Failure.AllSharesZero -> SplitPreview.NotReady(SplitPreview.Issue.ALL_SHARES_ZERO)
        is SplitResult.Failure.AdjustmentsExceedTotal ->
            SplitPreview.NotReady(SplitPreview.Issue.ADJUSTMENTS_EXCEED)
        is SplitResult.Failure.NegativeShare -> SplitPreview.NotReady(SplitPreview.Issue.NEGATIVE_SHARE)
        is SplitResult.Failure.ArithmeticOverflow -> SplitPreview.NotReady(SplitPreview.Issue.OVERFLOW)
        is SplitResult.Failure.NoParticipants -> SplitPreview.NotReady(SplitPreview.Issue.NO_PARTICIPANTS)
        is SplitResult.Failure -> SplitPreview.NotReady(SplitPreview.Issue.INVALID_INPUT)
    }
}
