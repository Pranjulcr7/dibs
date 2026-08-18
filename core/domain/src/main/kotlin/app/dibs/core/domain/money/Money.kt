// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.money

/**
 * An amount of money as a count of ISO-4217 **minor units** (cents, fils, yen…).
 *
 * Why a `Long` and not a decimal type: floating point cannot represent most cent
 * values exactly and drifts under accumulation, and even `BigDecimal` invites
 * accidental fractional cents. Integer minor units make every amount exact, make
 * equality trivial, and make "splits sum to the total" provable
 * (CLAUDE.md invariant 1). The currency travels separately — see [CurrencyCode] —
 * because a value class can hold only one field, and most arithmetic happens on
 * amounts already known to share a currency.
 */
@JvmInline
value class Money(val minorUnits: Long) : Comparable<Money> {

    val isZero: Boolean get() = minorUnits == 0L
    val isPositive: Boolean get() = minorUnits > 0L
    val isNegative: Boolean get() = minorUnits < 0L

    /**
     * Overflow-checked addition. Why checked: a silent wraparound in a money path
     * would corrupt balances undetectably (SPEC §6.1 case 5); an exception is a
     * bug we can see.
     */
    operator fun plus(other: Money): Money = Money(Math.addExact(minorUnits, other.minorUnits))

    /** Overflow-checked subtraction; see [plus] for why checked. */
    operator fun minus(other: Money): Money = Money(Math.subtractExact(minorUnits, other.minorUnits))

    /** Overflow-checked negation (`Long.MIN_VALUE` has no positive counterpart). */
    operator fun unaryMinus(): Money = Money(Math.negateExact(minorUnits))

    override fun compareTo(other: Money): Int = minorUnits.compareTo(other.minorUnits)

    companion object {
        val ZERO = Money(0)

        /**
         * Converts a whole number of major units (dollars, dinars, yen) using the
         * currency's ISO-4217 exponent. Why here and not `* 100` at call sites:
         * the exponent is 0 for JPY/KRW and 3 for KWD/BHD, so a hardcoded 100 is
         * a bug (SPEC §5.1). Overflow-checked for the same reason as [plus].
         */
        fun fromMajor(major: Long, currency: CurrencyCode): Money =
            Money(Math.multiplyExact(major, Currencies.minorUnitsPerMajor(currency)))
    }
}
