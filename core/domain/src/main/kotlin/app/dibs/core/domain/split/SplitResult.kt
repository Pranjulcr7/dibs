// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.split

import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money

/**
 * One member's computed share. [roundingAdjustment] is how many leftover minor
 * units largest-remainder allocation gave this member on top of their floored
 * exact share — surfaced because the UI must show who absorbed the extra cent
 * ("Alice +$0.01", SPEC §5.2); numbers that look wrong to a hand-checker destroy
 * trust even when they are right.
 */
data class Allocation(
    val memberId: MemberId,
    val amount: Money,
    val roundingAdjustment: Long,
)

/**
 * Outcome of [SplitCalculator.split]. Why a sealed result instead of exceptions:
 * every failure here is a *user input* state the UI must render specifically
 * (exact shortfall, who went negative, …) — SPEC §4.2 bans generic
 * "something went wrong" errors, so failures carry the numbers the screen needs.
 */
sealed interface SplitResult {

    /** Allocations sorted by member id, summing exactly to the requested total. */
    data class Success(val allocations: List<Allocation>) : SplitResult {

        /**
         * Members whose share came out to zero. Why surfaced: a $0.01 five-way
         * split legitimately zeroes four people, and the UI must warn rather
         * than let users discover it later (SPEC §6.1 case 2).
         */
        val zeroShareMembers: List<MemberId>
            get() = allocations.filter { it.amount.isZero }.map { it.memberId }
    }

    sealed interface Failure : SplitResult {

        /** A split needs at least one participant (or one line item). */
        data object NoParticipants : Failure

        /** Negative expense totals are rejected at input; refunds are their own transaction type (SPEC §6.1 case 4). */
        data class NegativeTotal(val total: Money) : Failure

        /** A share weight, percentage, or consumption weight was negative. */
        data class InvalidWeight(val memberId: MemberId, val weight: Long) : Failure

        /** Percentages must sum to exactly 100.00%; carries the exact shortfall for the UI (case 8). */
        data class PercentagesDoNotSumTo100(val sumBasisPoints: Long) : Failure {
            val shortfallBasisPoints: Long get() = FULL_TOTAL_BASIS_POINTS - sumBasisPoints

            companion object {
                const val FULL_TOTAL_BASIS_POINTS = 10_000L
            }
        }

        /** Exact amounts must sum to the total; carries the remainder for the one-tap fix (case 9). */
        data class AmountsDoNotSumToTotal(val assigned: Money, val total: Money) : Failure {
            val remaining: Money get() = total - assigned
        }

        /** Every share weight was zero, so there is nothing to divide by (case 10). */
        data object AllSharesZero : Failure

        /**
         * A computed or given share came out negative — e.g. a delta below the
         * equal share, or a discount exceeding a member's items.
         */
        data class NegativeShare(val memberId: MemberId) : Failure

        /** Adjustment deltas were set aside first and exceeded the total. */
        data class AdjustmentsExceedTotal(val adjustmentTotal: Money, val total: Money) : Failure

        /** Items + tax + tip must equal the expense total — the arithmetic gate of SPEC §5.8, applied in the domain. */
        data class ItemsDoNotSumToTotal(val computed: Money, val total: Money) : Failure

        /** Every line item must be assigned to someone before an itemized split can be computed. */
        data class ItemHasNoConsumers(val itemIndex: Int) : Failure

        /** Tax and tip are amounts added on top; negative values belong in discount line items instead. */
        data class NegativeTaxOrTip(val tax: Money, val tip: Money) : Failure

        /** A sum exceeded `Long` minor units; refused rather than silently wrapped (case 5). */
        data object ArithmeticOverflow : Failure
    }
}
