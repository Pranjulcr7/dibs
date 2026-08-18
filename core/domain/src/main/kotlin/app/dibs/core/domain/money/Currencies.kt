// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.money

/**
 * ISO-4217 minor-unit exponents.
 *
 * Why an embedded table instead of `android.icu.util.Currency` (which SPEC §5.1
 * mentions): the domain layer is pure JVM (CLAUDE.md invariant 4), and — more
 * importantly — two devices with different platform ICU data must still compute
 * byte-identical splits (invariant 6). A table compiled into the app is the same
 * on every device; platform ICU is not. `android.icu` remains the right tool for
 * *formatting* amounts in the UI layer. Recorded in DECISIONS.md.
 *
 * The table lists only the currencies whose exponent is not 2, per the current
 * ISO-4217 list; everything else, including unknown codes, defaults to 2.
 */
object Currencies {

    private const val DEFAULT_EXPONENT = 2

    private val EXPONENT_OVERRIDES: Map<String, Int> = mapOf(
        // Zero-decimal currencies.
        "BIF" to 0, "CLP" to 0, "DJF" to 0, "GNF" to 0, "ISK" to 0, "JPY" to 0,
        "KMF" to 0, "KRW" to 0, "PYG" to 0, "RWF" to 0, "UGX" to 0, "UYI" to 0,
        "VND" to 0, "VUV" to 0, "XAF" to 0, "XOF" to 0, "XPF" to 0,
        // Three-decimal currencies.
        "BHD" to 3, "IQD" to 3, "JOD" to 3, "KWD" to 3, "LYD" to 3, "OMR" to 3, "TND" to 3,
        // Four-decimal currencies.
        "CLF" to 4, "UYW" to 4,
    )

    /**
     * The number of decimal digits in the currency's minor unit (USD → 2,
     * JPY → 0, KWD → 3). Why a lookup and not an assumption: a hardcoded
     * exponent of 2 silently corrupts every yen and dinar amount (SPEC §5.1).
     */
    fun minorUnitExponent(currency: CurrencyCode): Int =
        EXPONENT_OVERRIDES.getOrDefault(currency.value, DEFAULT_EXPONENT)

    /**
     * How many minor units make one major unit (USD → 100, JPY → 1, KWD → 1000).
     * Why exposed: it is the single conversion factor between what users type
     * (major units) and what the ledger stores (minor units).
     */
    fun minorUnitsPerMajor(currency: CurrencyCode): Long {
        var result = 1L
        repeat(minorUnitExponent(currency)) { result *= 10 }
        return result
    }
}
