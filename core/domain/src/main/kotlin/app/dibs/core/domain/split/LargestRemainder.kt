// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.split

import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money
import java.math.BigInteger

/**
 * Largest-remainder allocation with a deterministic tiebreak — the single place
 * every split mode's rounding happens (SPEC §5.2).
 *
 * Why this method: flooring each exact share and then handing the leftover minor
 * units to the largest fractional remainders is the standard apportionment
 * answer to "someone must absorb the extra cent" — the sum is exact and the
 * distribution is as fair as integers allow. Why the UUID tiebreak: equal
 * remainders are common (any equal split), and "give it to the first in the
 * list" depends on insertion order, which differs between devices. Ascending
 * member UUID is stable everywhere, so every device computes the identical
 * allocation (CLAUDE.md invariant 6).
 */
internal object LargestRemainder {

    /**
     * Allocates [totalMinorUnits] proportionally to [weights].
     *
     * Preconditions (enforced by callers with typed [SplitResult.Failure]s):
     * total ≥ 0, every weight ≥ 0, at least one weight > 0.
     *
     * Why `BigInteger` internally: `total × weight` overflows `Long` long before
     * either factor does, and money must never wrap (SPEC §6.1 case 5). The
     * final per-member amounts are ≤ total, so they always fit back into `Long`.
     */
    fun allocate(totalMinorUnits: Long, weights: Map<MemberId, BigInteger>): List<Allocation> {
        require(totalMinorUnits >= 0) { "total must be non-negative" }
        val weightSum = weights.values.fold(BigInteger.ZERO, BigInteger::add)
        require(weightSum.signum() > 0) { "weight sum must be positive" }

        val total = BigInteger.valueOf(totalMinorUnits)
        val sortedMembers = weights.keys.sorted()

        data class Floored(val memberId: MemberId, val base: Long, val remainder: BigInteger)

        var allocated = 0L
        val floored = sortedMembers.map { memberId ->
            val numerator = total * weights.getValue(memberId)
            val (quotient, remainder) = numerator.divideAndRemainder(weightSum)
            val base = quotient.longValueExact()
            allocated = Math.addExact(allocated, base)
            Floored(memberId, base, remainder)
        }

        // Leftover minor units go one at a time to the largest fractional
        // remainders; ties by ascending member UUID, never by input order.
        var leftover = totalMinorUnits - allocated
        val byRemainder = floored.sortedWith(
            compareByDescending<Floored> { it.remainder }.thenBy { it.memberId },
        )
        val bonus = HashMap<MemberId, Long>(floored.size)
        for (entry in byRemainder) {
            if (leftover == 0L) break
            bonus[entry.memberId] = 1L
            leftover -= 1
        }

        return floored.map { entry ->
            val extra = bonus[entry.memberId] ?: 0L
            Allocation(entry.memberId, Money(entry.base + extra), roundingAdjustment = extra)
        }
    }
}
