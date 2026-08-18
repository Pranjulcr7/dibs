// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.balance

import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money

/**
 * A recorded expense as the balance computation sees it: who put money in
 * ([paidBy], possibly several people — FR-E3) and whose consumption it was
 * ([owedBy], the output of a [app.dibs.core.domain.split.SplitCalculator] run).
 *
 * Why the constructor enforces `Σ paidBy == Σ owedBy`: net balances sum to zero
 * *by construction* (SPEC §5.4) only if every expense is internally balanced.
 * Guarding it here means the invariant cannot be broken by any caller, rather
 * than being re-checked in every consumer.
 */
data class Expense(
    val id: ExpenseId,
    val currency: CurrencyCode,
    val paidBy: Map<MemberId, Money>,
    val owedBy: Map<MemberId, Money>,
) {
    init {
        require(paidBy.isNotEmpty()) { "An expense needs at least one payer" }
        require(paidBy.values.none { it.isNegative } && owedBy.values.none { it.isNegative }) {
            "Payments and shares must be non-negative; refunds are their own transaction type"
        }
        val paid = paidBy.values.fold(Money.ZERO, Money::plus)
        val owed = owedBy.values.fold(Money.ZERO, Money::plus)
        require(paid == owed) {
            "Payments (${paid.minorUnits}) must sum to the same total as shares (${owed.minorUnits})"
        }
    }
}

/**
 * A settle-up payment recorded as its own transaction type (FR-S3) rather than
 * a negative expense — because settlements must be listed, partial-paid (FR-S4),
 * and rendered differently from spending, and overloading Expense would leak
 * that distinction into every screen.
 */
data class SettlementPayment(
    val from: MemberId,
    val to: MemberId,
    val amount: Money,
    val currency: CurrencyCode,
) {
    init {
        require(amount.isPositive) { "A settlement payment must be a positive amount" }
        require(from != to) { "A settlement payment needs two distinct members" }
    }
}

/** One member's net position in one currency: positive = is owed, negative = owes. */
data class MemberBalance(
    val memberId: MemberId,
    val net: Money,
)
