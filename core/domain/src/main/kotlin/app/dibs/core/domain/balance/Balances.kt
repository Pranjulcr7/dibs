// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.balance

import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money

/**
 * Net-balance computation: `paid − owed` per member, per currency (SPEC §5.4).
 */
object Balances {

    /**
     * Folds expenses and settlements into each member's net position.
     *
     * Why grouped per currency and never summed across: $10 and ¥10 are not
     * addable without an exchange rate, and Dibs deliberately has none
     * (SPEC §6.5 case 53). Why the output is sorted by currency code and member
     * id: callers render and serialize this map, and iteration order must be
     * identical on every device (CLAUDE.md invariant 6) — insertion order is not.
     *
     * The result sums to zero per currency by construction, because every
     * [Expense] is internally balanced and every settlement moves equal and
     * opposite amounts.
     */
    fun net(
        expenses: List<Expense>,
        settlements: List<SettlementPayment> = emptyList(),
    ): Map<CurrencyCode, List<MemberBalance>> {
        val perCurrency = HashMap<CurrencyCode, HashMap<MemberId, Long>>()

        fun add(currency: CurrencyCode, member: MemberId, delta: Long) {
            val members = perCurrency.getOrPut(currency, ::HashMap)
            members[member] = Math.addExact(members.getOrDefault(member, 0L), delta)
        }

        expenses.forEach { expense ->
            expense.paidBy.forEach { (member, paid) -> add(expense.currency, member, paid.minorUnits) }
            expense.owedBy.forEach { (member, owed) -> add(expense.currency, member, -owed.minorUnits) }
        }
        settlements.forEach { settlement ->
            add(settlement.currency, settlement.from, settlement.amount.minorUnits)
            add(settlement.currency, settlement.to, -settlement.amount.minorUnits)
        }

        return perCurrency.entries
            .sortedBy { it.key.value }
            .associate { (currency, members) ->
                currency to members.entries
                    .sortedBy { it.key }
                    .map { (member, net) -> MemberBalance(member, Money(net)) }
            }
    }
}
