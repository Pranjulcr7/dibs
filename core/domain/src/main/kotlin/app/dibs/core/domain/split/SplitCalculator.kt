// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.split

import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.split.SplitResult.Failure
import java.math.BigInteger

/**
 * Turns a total plus a [SplitSpec] into exact per-member allocations.
 *
 * Why one entry point for all six modes: every mode funnels into the same
 * largest-remainder allocator, so the invariants — sum equals total, no
 * negative shares, deterministic tiebreaks — are enforced in one place and
 * proved once by the property tests instead of re-implemented per mode.
 */
object SplitCalculator {

    /**
     * Computes the split, or a typed [Failure] carrying exactly what the UI
     * needs to block the save (SPEC §6.1 cases 1-11). Never throws on user
     * input; throwing is reserved for programming errors.
     */
    fun split(total: Money, spec: SplitSpec): SplitResult {
        if (total.isNegative) return Failure.NegativeTotal(total)
        return try {
            when (spec) {
                is SplitSpec.Equal -> splitEqual(total, spec)
                is SplitSpec.ByShares -> splitByShares(total, spec)
                is SplitSpec.ByPercentage -> splitByPercentage(total, spec)
                is SplitSpec.ByExactAmounts -> splitByExactAmounts(total, spec)
                is SplitSpec.ByAdjustment -> splitByAdjustment(total, spec)
                is SplitSpec.Itemized -> splitItemized(total, spec)
            }
        } catch (_: ArithmeticException) {
            // Any checked Long overflow inside a mode surfaces as one typed
            // failure: amounts this size are user error, not a crash (case 5).
            Failure.ArithmeticOverflow
        }
    }

    private fun splitEqual(total: Money, spec: SplitSpec.Equal): SplitResult {
        if (spec.participants.isEmpty()) return Failure.NoParticipants
        val weights = spec.participants.associateWith { BigInteger.ONE }
        return SplitResult.Success(LargestRemainder.allocate(total.minorUnits, weights))
    }

    private fun splitByShares(total: Money, spec: SplitSpec.ByShares): SplitResult {
        if (spec.shares.isEmpty()) return Failure.NoParticipants
        firstNegative(spec.shares)?.let { (member, weight) -> return Failure.InvalidWeight(member, weight) }
        if (spec.shares.values.all { it == 0L }) return Failure.AllSharesZero
        val weights = spec.shares.mapValues { BigInteger.valueOf(it.value) }
        return SplitResult.Success(LargestRemainder.allocate(total.minorUnits, weights))
    }

    private fun splitByPercentage(total: Money, spec: SplitSpec.ByPercentage): SplitResult {
        if (spec.basisPoints.isEmpty()) return Failure.NoParticipants
        firstNegative(spec.basisPoints)?.let { (member, bps) -> return Failure.InvalidWeight(member, bps) }
        val sum = spec.basisPoints.values.fold(0L, Math::addExact)
        if (sum != Failure.PercentagesDoNotSumTo100.FULL_TOTAL_BASIS_POINTS) {
            return Failure.PercentagesDoNotSumTo100(sum)
        }
        val weights = spec.basisPoints.mapValues { BigInteger.valueOf(it.value) }
        return SplitResult.Success(LargestRemainder.allocate(total.minorUnits, weights))
    }

    private fun splitByExactAmounts(total: Money, spec: SplitSpec.ByExactAmounts): SplitResult {
        if (spec.amounts.isEmpty()) return Failure.NoParticipants
        spec.amounts.entries
            .filter { it.value.isNegative }
            .minByOrNull { it.key }
            ?.let { return Failure.NegativeShare(it.key) }
        val assigned = spec.amounts.values.fold(Money.ZERO, Money::plus)
        if (assigned != total) return Failure.AmountsDoNotSumToTotal(assigned, total)
        val allocations = spec.amounts.entries
            .sortedBy { it.key }
            .map { Allocation(it.key, it.value, roundingAdjustment = 0L) }
        return SplitResult.Success(allocations)
    }

    private fun splitByAdjustment(total: Money, spec: SplitSpec.ByAdjustment): SplitResult {
        val participants = spec.participants + spec.adjustments.keys
        if (participants.isEmpty()) return Failure.NoParticipants
        val adjustmentTotal = spec.adjustments.values.fold(Money.ZERO, Money::plus)
        val base = total - adjustmentTotal
        if (base.isNegative) return Failure.AdjustmentsExceedTotal(adjustmentTotal, total)

        val equalWeights = participants.associateWith { BigInteger.ONE }
        val equalAllocations = LargestRemainder.allocate(base.minorUnits, equalWeights)

        val allocations = equalAllocations.map { allocation ->
            val delta = spec.adjustments[allocation.memberId] ?: Money.ZERO
            allocation.copy(amount = allocation.amount + delta)
        }
        allocations.filter { it.amount.isNegative }.minByOrNull { it.memberId }?.let {
            return Failure.NegativeShare(it.memberId)
        }
        return SplitResult.Success(allocations)
    }

    private fun splitItemized(total: Money, spec: SplitSpec.Itemized): SplitResult {
        validateItemized(total, spec)?.let { return it }

        // Exact per-member subtotals as integers over a common denominator: for
        // item j with weight sum W(j), scale by D / W(j) where D = Π W(j).
        // Why rationals until the very end: rounding intermediate subtotals
        // compounds errors; SPEC §5.3 requires one rounding against the true total.
        val denominators = spec.items.map { item ->
            item.consumers.values.fold(BigInteger.ZERO) { acc, w -> acc + BigInteger.valueOf(w) }
        }
        val commonDenominator = denominators.fold(BigInteger.ONE, BigInteger::multiply)
        val subtotals = HashMap<MemberId, BigInteger>()
        spec.items.forEachIndexed { index, item ->
            val scale = commonDenominator / denominators[index]
            val price = BigInteger.valueOf(item.price.minorUnits)
            item.consumers.forEach { (member, weight) ->
                val contribution = price * BigInteger.valueOf(weight) * scale
                subtotals.merge(member, contribution, BigInteger::add)
            }
        }

        subtotals.entries
            .filter { it.value.signum() < 0 }
            .minByOrNull { it.key }
            ?.let { return Failure.NegativeShare(it.key) }

        // Zero item subtotal with a nonzero total (tax/tip on zero-priced items):
        // nothing to prorate against, so divide equally — the simplest behavior a
        // user could predict. Recorded in DECISIONS.md.
        val subtotalSum = subtotals.values.fold(BigInteger.ZERO, BigInteger::add)
        val weights = if (subtotalSum.signum() == 0) {
            subtotals.keys.associateWith { BigInteger.ONE }
        } else {
            subtotals
        }
        return SplitResult.Success(LargestRemainder.allocate(total.minorUnits, weights))
    }

    private fun validateItemized(total: Money, spec: SplitSpec.Itemized): Failure? {
        if (spec.tax.isNegative || spec.tip.isNegative) return Failure.NegativeTaxOrTip(spec.tax, spec.tip)
        if (spec.items.isEmpty()) return Failure.NoParticipants
        spec.items.forEachIndexed { index, item ->
            if (item.consumers.isEmpty() || item.consumers.values.all { it == 0L }) {
                return Failure.ItemHasNoConsumers(index)
            }
            firstNegative(item.consumers)?.let { (member, weight) -> return Failure.InvalidWeight(member, weight) }
        }
        val itemSum = spec.items.fold(Money.ZERO) { acc, item -> acc + item.price }
        val computed = itemSum + spec.tax + spec.tip
        if (computed != total) return Failure.ItemsDoNotSumToTotal(computed, total)
        return null
    }

    /** Deterministically the *lowest-id* offender, so error messages match across devices. */
    private fun firstNegative(weights: Map<MemberId, Long>): Pair<MemberId, Long>? =
        weights.entries.filter { it.value < 0 }
            .minByOrNull { it.key }
            ?.let { it.key to it.value }
}
