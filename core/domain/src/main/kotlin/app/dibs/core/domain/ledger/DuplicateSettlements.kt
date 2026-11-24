// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.SettlementId

/**
 * Finds repayments that were probably recorded twice (SPEC §6.3 case 32).
 *
 * Why this exists: with no server, two people can each record "Bob paid Alice
 * $15" on their own phone before the phones have exchanged updates. Both ops
 * are valid and both are kept — the ledger never drops a financial fact — but
 * the result is Bob showing as $15 in credit, which looks like a bug unless the
 * screen says what probably happened.
 *
 * A pair is suspect only when both things hold: the two payments are identical
 * (same payer, payee, amount, currency), *and* the payer has ended up owed
 * money by the payee. Identical payments alone are ordinary — rent in two
 * equal instalments — so without the overpayment nothing is flagged. Like
 * [DuplicateMembers], this only detects; undoing one is the user's decision.
 */
object DuplicateSettlements {

    private const val WRITE_OFF = "writeoff"

    fun suspects(state: GroupState): Set<SettlementId> {
        val debts = state.pairwiseDebts()
        return state.settlements.values
            .filter { it.kind != WRITE_OFF }
            .groupBy { listOf(it.from.value, it.to.value, it.currency.value, it.amount.minorUnits.toString()) }
            .values
            .filter { same -> same.size > 1 }
            .filter { same ->
                val payment = same.first()
                // Overpaid: the one who received the money now owes the one who paid it.
                debts[payment.currency].orEmpty().any { it.from == payment.to && it.to == payment.from }
            }
            .flatMap { same -> same.map { it.id } }
            .toSet()
    }
}
