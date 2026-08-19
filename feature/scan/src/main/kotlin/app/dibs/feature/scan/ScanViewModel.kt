// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.scan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.receipt.ReceiptDraft
import app.dibs.core.domain.receipt.ReceiptFingerprint
import app.dibs.core.domain.receipt.ReceiptFrame
import app.dibs.core.domain.receipt.ReceiptHandoff
import app.dibs.core.domain.receipt.ReceiptItem
import app.dibs.core.domain.receipt.ReceiptParser
import app.dibs.core.domain.receipt.RuleBasedLayoutParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Capture → parse → mandatory review (FR-R3). Review edits live here so the
 * arithmetic gate re-runs on every change (FR-R4) and the hand-off to the
 * expense editor is exactly what the user approved — nothing the parser
 * produced ever becomes an expense unseen.
 */
sealed interface ScanStep {
    data class Capture(val frameCount: Int, val parseFailed: Failure? = null) : ScanStep {
        enum class Failure { NOTHING_FOUND, TIMED_OUT }
    }

    data object Parsing : ScanStep

    data class Review(
        val draft: ReceiptDraft,
        /** Edge 47: another expense already carries this receipt's fingerprint. */
        val looksAlreadyAdded: Boolean,
        val fingerprint: String,
        val currency: CurrencyCode,
    ) : ScanStep
}

@HiltViewModel
@Suppress("TooManyFunctions") // one setter per review-screen field is the honest shape
class ScanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val parser: ReceiptParser,
    private val ledger: Ledger,
) : ViewModel() {

    private val groupId = GroupId(checkNotNull(savedStateHandle["groupId"]) { "groupId nav argument missing" })

    private val frames = mutableListOf<ReceiptFrame>()

    private val _step = MutableStateFlow<ScanStep>(ScanStep.Capture(0))
    val step: StateFlow<ScanStep> = _step.asStateFlow()

    /** The first captured frame's bytes, for storing as the receipt photo (FR-E8). */
    val firstFrameBytes: ByteArray? get() = frames.firstOrNull()?.jpegBytes

    fun addFrame(jpegBytes: ByteArray) {
        frames += ReceiptFrame(jpegBytes)
        _step.value = ScanStep.Capture(frames.size)
    }

    fun removeLastFrame() {
        if (frames.isNotEmpty()) frames.removeAt(frames.lastIndex)
        _step.value = ScanStep.Capture(frames.size)
    }

    fun parse() {
        if (frames.isEmpty()) return
        _step.value = ScanStep.Parsing
        viewModelScope.launch {
            when (val result = parser.parse(frames.toList())) {
                is ReceiptParser.Result.Parsed -> enterReview(result.draft)
                ReceiptParser.Result.NothingFound ->
                    _step.value = ScanStep.Capture(frames.size, ScanStep.Capture.Failure.NOTHING_FOUND)
                ReceiptParser.Result.TimedOut ->
                    _step.value = ScanStep.Capture(frames.size, ScanStep.Capture.Failure.TIMED_OUT)
            }
        }
    }

    private suspend fun enterReview(draft: ReceiptDraft) {
        val fingerprint = ReceiptFingerprint.of(draft)
        val state = ledger.state(groupId)
        val duplicate = state.activeExpenses.any {
            it.fields.receiptRef?.startsWith(fingerprint) == true
        }
        val currency = state.meta?.defaultCurrency ?: CurrencyCode("USD")
        _step.value = ScanStep.Review(draft, duplicate, fingerprint, currency)
    }

    // -- review edits (every one re-runs the gate) -------------------------

    fun updateItemLabel(index: Int, label: String) = editDraft { draft ->
        draft.copy(items = draft.items.mapIndexed { i, item -> if (i == index) item.copy(label = label) else item })
    }

    fun updateItemAmount(index: Int, amountMinor: Long) = editDraft { draft ->
        draft.copy(
            items = draft.items.mapIndexed { i, item ->
                if (i == index) item.copy(totalMinor = amountMinor) else item
            },
        )
    }

    fun removeItem(index: Int) = editDraft { draft ->
        draft.copy(items = draft.items.filterIndexed { i, _ -> i != index })
    }

    fun addItem() = editDraft { draft ->
        draft.copy(items = draft.items + ReceiptItem(label = "", totalMinor = 0))
    }

    fun setTax(taxMinor: Long?) = editDraft { it.copy(taxMinor = taxMinor) }

    /** Edge 42: the tip is often handwritten after printing — always editable. */
    fun setTip(tipMinor: Long?) = editDraft { it.copy(tipMinor = tipMinor) }

    fun setTotal(totalMinor: Long?) = editDraft { it.copy(totalMinor = totalMinor) }

    private fun editDraft(transform: (ReceiptDraft) -> ReceiptDraft) {
        _step.update { step ->
            if (step is ScanStep.Review) {
                val edited = RuleBasedLayoutParser.withRecomputedGate(transform(step.draft))
                step.copy(draft = edited, fingerprint = ReceiptFingerprint.of(edited))
            } else {
                step
            }
        }
    }

    /** The approved review as an editor hand-off; [receiptRef] is the stored image's name, if saved. */
    fun handoff(receiptRef: String?): ReceiptHandoff? {
        val review = _step.value as? ScanStep.Review ?: return null
        return ReceiptHandoff.of(review.draft, receiptRef)
    }
}
