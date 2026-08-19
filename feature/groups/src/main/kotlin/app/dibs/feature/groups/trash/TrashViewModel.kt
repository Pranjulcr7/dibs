// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.trash

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.ledger.ExpenseEvent
import app.dibs.core.domain.ledger.ExpenseState
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrashRow(
    val expense: ExpenseState,
    /** Display-only: derived from the delete op's wall clock (FR-E5's 30-day window). */
    val daysLeft: Int,
)

/**
 * The 30-day trash (FR-E5). Nothing is ever purged from the log — after the
 * window the tombstone simply stops being offered for restore here. Restoring
 * is one op; the expense returns with any edits that happened while deleted
 * (edge 21).
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledger: Ledger,
) : ViewModel() {

    /** Display-only clock; swapped in tests (Hilt cannot inject lambdas). */
    internal var wallClock: () -> Long = System::currentTimeMillis

    private val groupId = GroupId(checkNotNull(savedStateHandle["groupId"]) { "groupId nav argument missing" })

    val rows: StateFlow<List<TrashRow>?> = ledger.observeState(groupId).map { state ->
        val now = wallClock()
        state.trash.mapNotNull { expense ->
            val deletedAt = expense.history.lastOrNull { it.kind == ExpenseEvent.Kind.DELETED }
                ?.wallClockMillis ?: return@mapNotNull null
            val daysLeft = TRASH_DAYS - ((now - deletedAt) / MILLIS_PER_DAY).toInt()
            if (daysLeft <= 0) null else TrashRow(expense, daysLeft)
        }.sortedBy { it.daysLeft }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    fun restore(expenseId: ExpenseId) {
        viewModelScope.launch { ledger.append(groupId, OpPayload.RestoreExpense(expenseId)) }
    }

    private companion object {
        const val TRASH_DAYS = 30
        const val MILLIS_PER_DAY = 86_400_000L
        const val STOP_TIMEOUT = 5_000L
    }
}
