// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One feed row: an expense somewhere across all groups (FR — Activity tab). */
data class ActivityItem(
    val groupId: GroupId,
    val expenseId: ExpenseId,
    val groupLabel: String,
    val description: String,
    val amount: Money,
    val currency: CurrencyCode,
    val payerName: String,
    val dateEpochDay: Long,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActivityViewModel @Inject constructor(
    ledger: Ledger,
) : ViewModel() {

    val items: StateFlow<List<ActivityItem>?> =
        ledger.observeGroupIds().flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(ids.sorted().map { ledger.observeState(it) }) { states ->
                    states.flatMap { state ->
                        val label = listOfNotNull(state.meta?.emoji, state.meta?.name).joinToString(" ")
                        state.activeExpenses.map { expense ->
                            ActivityItem(
                                groupId = state.groupId,
                                expenseId = expense.id,
                                groupLabel = label,
                                description = expense.fields.description,
                                amount = expense.fields.total,
                                currency = expense.fields.currency,
                                payerName = expense.fields.paidBy.keys.firstOrNull()
                                    ?.let { state.members[it]?.name }.orEmpty(),
                                dateEpochDay = expense.fields.dateEpochDay,
                            )
                        }
                    }.sortedWith(
                        // Civil-date recency; ids break ties deterministically.
                        compareByDescending<ActivityItem> { it.dateEpochDay }.thenByDescending { it.expenseId },
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
