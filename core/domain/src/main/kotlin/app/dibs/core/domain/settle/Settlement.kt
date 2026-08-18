// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.settle

import app.dibs.core.domain.balance.MemberBalance
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money
import java.util.PriorityQueue

/** A suggested settle-up payment: [from] (a debtor) pays [to] (a creditor). */
data class Transfer(
    val from: MemberId,
    val to: MemberId,
    val amount: Money,
)

/**
 * Settlement simplification (SPEC §5.4): collapse a web of debts into few
 * direct payments.
 */
object Settlement {

    /**
     * Greedy max-debtor / max-creditor pairing over two priority queues.
     *
     * This is **not** the provably minimal number of transfers — minimizing
     * transfer count exactly is NP-hard — it is the standard greedy bound of at
     * most n−1 transfers for n members with nonzero balances, which is what
     * users actually need: few, predictable payments.
     *
     * Why the ordering is `(amount desc, memberId asc)` on both queues: two
     * friends looking at their own phones must see the *same* suggested
     * payments, so every choice the greedy makes is keyed to stable ids, never
     * to input order (CLAUDE.md invariant 6). Termination: each pairing zeroes
     * at least one of the two members, so at most n−1 pairings occur.
     *
     * Input must be one currency's balances, summing to zero — which
     * [app.dibs.core.domain.balance.Balances.net] guarantees by construction;
     * anything else is a programming error and throws.
     */
    fun simplify(balances: List<MemberBalance>): List<Transfer> {
        require(balances.map { it.memberId }.toSet().size == balances.size) {
            "Duplicate member in balances"
        }
        val sum = balances.fold(0L) { acc, balance -> Math.addExact(acc, balance.net.minorUnits) }
        require(sum == 0L) { "Balances must sum to zero, got $sum" }

        val order = compareByDescending<Pair<MemberId, Long>> { it.second }.thenBy { it.first }
        val creditors = PriorityQueue(balances.size.coerceAtLeast(1), order)
        val debtors = PriorityQueue(balances.size.coerceAtLeast(1), order)
        balances.forEach { balance ->
            when {
                balance.net.isPositive -> creditors.add(balance.memberId to balance.net.minorUnits)
                balance.net.isNegative -> debtors.add(balance.memberId to -balance.net.minorUnits)
            }
        }

        val transfers = ArrayList<Transfer>()
        while (creditors.isNotEmpty() && debtors.isNotEmpty()) {
            val (creditor, credit) = creditors.poll()
            val (debtor, debt) = debtors.poll()
            val amount = minOf(credit, debt)
            transfers.add(Transfer(from = debtor, to = creditor, amount = Money(amount)))
            if (credit > amount) creditors.add(creditor to credit - amount)
            if (debt > amount) debtors.add(debtor to debt - amount)
        }
        return transfers
    }
}
