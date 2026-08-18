// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.split

import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money

/**
 * How an expense total is divided among participants — the six split modes of
 * SPEC §2.2 FR-E2. A spec is pure input data; validation and arithmetic live in
 * [SplitCalculator] so a spec can be built incrementally by UI state without
 * ever being half-invalid in the domain.
 */
sealed interface SplitSpec {

    /** Everyone selected pays the same, give or take the deterministically-placed leftover cents. */
    data class Equal(val participants: Set<MemberId>) : SplitSpec

    /**
     * Each member is assigned an explicit amount; the amounts must sum exactly to
     * the total or the calculator blocks the save (SPEC §6.1 case 9).
     */
    data class ByExactAmounts(val amounts: Map<MemberId, Money>) : SplitSpec

    /**
     * Percentages expressed as integer **basis points** (100.00% = 10 000) that
     * must sum exactly to 10 000. Why basis points and not a fraction type or
     * float: they carry the two decimal places users type, in an integer, so the
     * "sums to 100%" check is exact (SPEC §6.1 case 8) and no float ever enters
     * a money path (CLAUDE.md invariant 1).
     */
    data class ByPercentage(val basisPoints: Map<MemberId, Long>) : SplitSpec

    /** Integer weights, e.g. 2 shares for the couple and 1 for the single (Splitwise's "shares"). */
    data class ByShares(val shares: Map<MemberId, Long>) : SplitSpec

    /**
     * Splitwise's "+/-": each participant gets an equal share of what remains
     * after the per-member deltas are set aside, plus their own delta. Members
     * appearing only in [adjustments] are treated as participants too.
     */
    data class ByAdjustment(
        val participants: Set<MemberId>,
        val adjustments: Map<MemberId, Money>,
    ) : SplitSpec

    /**
     * Line items assigned to people, with tax and tip prorated by each member's
     * item subtotal (SPEC §5.3). The expense total must equal
     * items + tax + tip; the calculator refuses mismatches rather than hiding a
     * receipt that doesn't add up.
     */
    data class Itemized(
        val items: List<LineItem>,
        val tax: Money,
        val tip: Money,
    ) : SplitSpec
}

/**
 * One receipt line. [consumers] maps each sharer to an integer consumption
 * weight — "a ate two thirds of the nachos" is `a to 2, b to 1`. Why integer
 * weights instead of fractions: they express every realistic sharing pattern,
 * stay exact, and keep floats out (CLAUDE.md invariant 1). A negative [price]
 * is a discount or coupon and reduces the subtotal used for tax proration
 * (SPEC §6.4 case 41).
 */
data class LineItem(
    val price: Money,
    val consumers: Map<MemberId, Long>,
    val description: String? = null,
)
