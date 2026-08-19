// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.detail

import app.dibs.core.domain.ledger.ExpenseState
import app.dibs.core.domain.model.MemberId

/**
 * Pure expense filtering (FR-E9: text, member, category — date-range and
 * amount filters are still open, tracked in PROGRESS). Pure so it is testable
 * without Compose and the list UI stays dumb.
 */
object ExpenseFilter {

    fun apply(expenses: List<ExpenseState>, query: String, member: MemberId?, category: String?): List<ExpenseState> {
        val needle = query.trim().lowercase()
        return expenses.filter { expense ->
            val matchesText = needle.isEmpty() ||
                expense.fields.description.lowercase().contains(needle) ||
                expense.fields.note?.lowercase()?.contains(needle) == true
            val matchesMember = member == null ||
                expense.fields.paidBy.containsKey(member) ||
                expense.fields.owedBy.containsKey(member)
            val matchesCategory = category == null || expense.fields.category == category
            matchesText && matchesMember && matchesCategory
        }
    }
}
