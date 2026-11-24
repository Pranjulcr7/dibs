// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money

/**
 * One member's position in one currency, with the expenses that produced it
 * (FR-S5).
 *
 * Why this exists: SPEC §1 forbids showing a number the user cannot verify. A
 * net balance of "−$42.50" is such a number until it can be opened into "you
 * paid these, your share of those was this, and you sent Bob that".
 *
 * [net] is `paid − share + paymentsMade − paymentsReceived` — the same
 * arithmetic [app.dibs.core.domain.balance.Balances] performs, over the same
 * active expenses and settlements, so the drill-down and the balances list can
 * never disagree (a property test holds them equal).
 */
data class MemberStatement(
    val currency: CurrencyCode,
    val paid: Money,
    val share: Money,
    val paymentsMade: Money,
    val paymentsReceived: Money,
    val lines: List<Line>,
) {
    val net: Money get() = paid - share + paymentsMade - paymentsReceived

    /** This member's part of one expense. */
    data class Line(
        val expenseId: ExpenseId,
        val description: String,
        val dateEpochDay: Long,
        val paid: Money,
        val share: Money,
    )

    companion object {

        /**
         * Statements for [memberId], one per currency they appear in, sorted by
         * currency code; lines newest first, ties by expense id — never by
         * iteration order (CLAUDE.md invariant 6). Deleted expenses are left
         * out, exactly as they are left out of balances.
         */
        fun of(state: GroupState, memberId: MemberId): List<MemberStatement> {
            val lines = state.activeExpenses
                .filter { memberId in it.fields.paidBy || memberId in it.fields.owedBy }
                .groupBy { it.fields.currency }
                .mapValues { (_, expenses) ->
                    expenses
                        .sortedWith(compareByDescending<ExpenseState> { it.fields.dateEpochDay }.thenBy { it.id })
                        .map {
                            Line(
                                expenseId = it.id,
                                description = it.fields.description,
                                dateEpochDay = it.fields.dateEpochDay,
                                paid = it.fields.paidBy[memberId] ?: Money.ZERO,
                                share = it.fields.owedBy[memberId] ?: Money.ZERO,
                            )
                        }
                }
            val settlements = state.settlements.values.filter { it.from == memberId || it.to == memberId }
            val currencies = (lines.keys + settlements.map { it.currency }).sortedBy { it.value }
            return currencies.map { currency ->
                val own = lines[currency].orEmpty()
                val inCurrency = settlements.filter { it.currency == currency }
                MemberStatement(
                    currency = currency,
                    paid = own.fold(Money.ZERO) { sum, line -> sum + line.paid },
                    share = own.fold(Money.ZERO) { sum, line -> sum + line.share },
                    paymentsMade = inCurrency.filter { it.from == memberId }
                        .fold(Money.ZERO) { sum, s -> sum + s.amount },
                    paymentsReceived = inCurrency.filter { it.to == memberId }
                        .fold(Money.ZERO) { sum, s -> sum + s.amount },
                    lines = own,
                )
            }
        }
    }
}
