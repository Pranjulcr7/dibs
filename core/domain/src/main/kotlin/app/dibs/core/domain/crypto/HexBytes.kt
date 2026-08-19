// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.crypto

/** Lowercase hex helpers; hand-rolled for the same reason as in OpFactory (DECISIONS.md D19). */
object HexBytes {

    private const val DIGITS = "0123456789abcdef"

    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(DIGITS[value ushr 4]).append(DIGITS[value and 0x0F])
        }
        return out.toString()
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { index ->
            val hi = DIGITS.indexOf(hex[index * 2].lowercaseChar())
            val lo = DIGITS.indexOf(hex[index * 2 + 1].lowercaseChar())
            require(hi >= 0 && lo >= 0) { "Invalid hex character in '$hex'" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
