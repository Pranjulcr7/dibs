// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.money

/**
 * An ISO-4217 alphabetic currency code such as `"USD"` or `"JPY"`.
 *
 * Why a value class over a raw string: money amounts are meaningless without
 * their currency, and a distinct type stops a member name or group id from being
 * passed where a currency belongs. Validation is strict at construction so a
 * malformed code can never reach the ledger.
 */
@JvmInline
value class CurrencyCode(val value: String) {
    init {
        require(value.length == 3 && value.all { it in 'A'..'Z' }) {
            "Currency code must be exactly three uppercase ASCII letters, got '$value'"
        }
    }
}
