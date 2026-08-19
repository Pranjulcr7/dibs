// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.receipt

import app.dibs.core.domain.crypto.HexBytes
import org.apache.commons.codec.digest.Blake3

/**
 * A near-identity fingerprint for a parsed receipt (edge 47: the same paper
 * scanned twice should trigger "did you mean to add this again?"). Hashing
 * the *parsed content* — normalized labels, amounts, totals — rather than
 * image bytes makes two photos of the same receipt collide on purpose, while
 * different receipts from the same shop don't.
 */
object ReceiptFingerprint {

    fun of(draft: ReceiptDraft): String {
        val canonical = buildString {
            draft.items.forEach { item ->
                append(item.label.lowercase().replace(Regex("\\s+"), " ").trim())
                append('|').append(item.quantity).append('|').append(item.totalMinor).append('\n')
            }
            append("t|").append(draft.totalMinor ?: -1).append('\n')
            append("x|").append(draft.taxMinor ?: -1).append('\n')
        }
        val hash = ByteArray(FINGERPRINT_BYTES)
        Blake3.initHash().update(canonical.toByteArray(Charsets.UTF_8)).doFinalize(hash)
        return HexBytes.toHex(hash)
    }

    private const val FINGERPRINT_BYTES = 16
}
