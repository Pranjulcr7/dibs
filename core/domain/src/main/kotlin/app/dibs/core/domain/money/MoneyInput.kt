// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.money

/**
 * Parsing of user-typed amounts into minor units — the inverse of display
 * formatting, and like it, exponent-driven per currency (SPEC §5.1).
 *
 * Why fail-closed everywhere: an amount field that "helpfully" rounds
 * `10.123` USD or accepts `-5` would put values into the ledger the user
 * never typed. Null means "keep the save button disabled", never "guess".
 * Both `.` and `,` are accepted as the decimal separator because keyboards
 * differ by locale; grouping separators are rejected outright rather than
 * risking `10,50` (comma-decimal) being read as `1050`.
 */
object MoneyInput {

    fun parse(text: String, currency: CurrencyCode): Money? {
        val exponent = Currencies.minorUnitExponent(currency)
        val parts = splitDecimal(text.trim()) ?: return null
        val (whole, fraction) = parts
        if (fraction.length > exponent) return null
        val paddedFraction = fraction.padEnd(exponent, '0')
        return try {
            val major = if (whole.isEmpty()) 0L else whole.toLongOrNull() ?: return null
            val minorPart = if (paddedFraction.isEmpty()) 0L else paddedFraction.toLong()
            Money(
                Math.addExact(
                    Math.multiplyExact(major, Currencies.minorUnitsPerMajor(currency)),
                    minorPart,
                ),
            )
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** Signed variant for adjustment deltas — the one place negative input is meaningful. */
    fun parseSigned(text: String, currency: CurrencyCode): Money? {
        val trimmed = text.trim()
        return if (trimmed.startsWith("-")) {
            parse(trimmed.drop(1), currency)?.let { Money(Math.negateExact(it.minorUnits)) }
        } else {
            parse(trimmed, currency)
        }
    }

    /** Minor units back to plain editable text ("1050" USD -> "10.50") — the parse inverse for edit prefills. */
    fun toPlainString(money: Money, currency: CurrencyCode): String {
        val exponent = Currencies.minorUnitExponent(currency)
        if (exponent == 0) return money.minorUnits.toString()
        val negative = money.minorUnits < 0
        val abs = if (negative) -money.minorUnits else money.minorUnits
        val perMajor = Currencies.minorUnitsPerMajor(currency)
        val whole = abs / perMajor
        val fraction = (abs % perMajor).toString().padStart(exponent, '0')
        return (if (negative) "-" else "") + "$whole.$fraction"
    }

    /** Percentages as basis points (100.00% = 10 000), at most two decimals — see D2. */
    fun parseBasisPoints(text: String): Long? {
        val parts = splitDecimal(text.trim()) ?: return null
        val (whole, fraction) = parts
        if (fraction.length > 2) return null
        val padded = fraction.padEnd(2, '0')
        val major = if (whole.isEmpty()) 0L else whole.toLongOrNull() ?: return null
        return try {
            Math.addExact(Math.multiplyExact(major, 100L), if (padded.isEmpty()) 0L else padded.toLong())
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** Splits on a single `.` or `,`; null for anything else non-numeric. */
    private fun splitDecimal(text: String): Pair<String, String>? {
        if (text.isEmpty()) return null
        val separatorCount = text.count { it == '.' || it == ',' }
        if (separatorCount > 1) return null
        val normalized = text.replace(',', '.')
        val pieces = normalized.split('.')
        val whole = pieces[0]
        val fraction = pieces.getOrElse(1) { "" }
        if (whole.isEmpty() && fraction.isEmpty()) return null
        if (!whole.all(Char::isDigit) || !fraction.all(Char::isDigit)) return null
        return whole to fraction
    }
}
