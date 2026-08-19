// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.balance

import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.settle.Settlement

/** A direct debt: [from] owes [to]. The raw who-owes-whom view (FR-S1). */
data class PairDebt(
    val from: MemberId,
    val to: MemberId,
    val amount: Money,
)

/**
 * Raw pairwise balances — the default, unsurprising view (FR-S1/S2: global
 * simplification is opt-in because "why am I paying Dave?" destroys trust).
 *
 * Each expense is decomposed *by itself* into direct debts using the same
 * deterministic greedy pairing as settlement simplification. For the common
 * single-payer case this is literally "each participant owes the payer their
 * share, to the cent"; for multi-payer expenses it stays exact where
 * proportional attribution would drift by rounding. Because each expense's
 * decomposition is internally exact, every member's pairwise net equals their
 * overall net balance — property-tested, not assumed.
 */
object PairwiseBalances {

    fun compute(
        expenses: List<Expense>,
        settlements: List<SettlementPayment> = emptyList(),
    ): Map<CurrencyCode, List<PairDebt>> {
        // (currency, debtor, creditor) -> minor units; nettable, so signed via ordering below.
        val matrix = HashMap<Triple<CurrencyCode, MemberId, MemberId>, Long>()

        fun add(currency: CurrencyCode, from: MemberId, to: MemberId, minorUnits: Long) {
            val key = Triple(currency, from, to)
            matrix[key] = Math.addExact(matrix.getOrDefault(key, 0L), minorUnits)
        }

        expenses.forEach { expense ->
            val perMember = HashMap<MemberId, Long>()
            expense.paidBy.forEach { (member, paid) ->
                perMember[member] = Math.addExact(perMember.getOrDefault(member, 0L), paid.minorUnits)
            }
            expense.owedBy.forEach { (member, owed) ->
                perMember[member] = Math.subtractExact(perMember.getOrDefault(member, 0L), owed.minorUnits)
            }
            val balances = perMember.entries
                .sortedBy { it.key }
                .map { MemberBalance(it.key, Money(it.value)) }
            Settlement.simplify(balances).forEach { transfer ->
                add(expense.currency, transfer.from, transfer.to, transfer.amount.minorUnits)
            }
        }
        settlements.forEach { settlement ->
            // Paying someone reduces what you owe them (or creates their debt to you).
            add(settlement.currency, settlement.from, settlement.to, -settlement.amount.minorUnits)
        }

        // Net each unordered pair into at most one directed debt, deterministically.
        val result = HashMap<CurrencyCode, MutableList<PairDebt>>()
        matrix.keys
            .map { (currency, from, to) -> Triple(currency, minOf(from, to), maxOf(from, to)) }
            .distinct()
            .forEach { (currency, low, high) ->
                val lowOwes = matrix.getOrDefault(Triple(currency, low, high), 0L)
                val highOwes = matrix.getOrDefault(Triple(currency, high, low), 0L)
                val net = Math.subtractExact(lowOwes, highOwes)
                if (net > 0) {
                    result.getOrPut(currency) { mutableListOf() }.add(PairDebt(low, high, Money(net)))
                } else if (net < 0) {
                    result.getOrPut(currency) { mutableListOf() }.add(PairDebt(high, low, Money(-net)))
                }
            }
        return result.entries
            .sortedBy { it.key.value }
            .associate { (currency, debts) ->
                currency to debts.sortedWith(compareBy({ it.from }, { it.to }))
            }
    }
}
