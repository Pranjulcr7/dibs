// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.crypto.HexBytes
import app.dibs.core.domain.crypto.IdentityKey
import app.dibs.core.domain.model.MemberId
import org.apache.commons.codec.digest.Blake3

/**
 * Human-facing handles (SPEC §5.9): display name + " #" + the first 4 hex of
 * BLAKE3 over the identity public key; a 4-digit collision inside a group
 * extends *both* to 6, deterministically.
 *
 * The name half is self-chosen and unverified; only the fingerprint binds to a
 * key. UIs must render the fingerprint distinctly (monospace, muted) and never
 * style the name as if it were verified.
 */
object Handles {

    const val SHORT_DIGITS = 4
    const val EXTENDED_DIGITS = 6

    /** The full fingerprint (64 hex chars) of an identity key. */
    fun fingerprint(key: IdentityKey): String {
        val hash = ByteArray(FINGERPRINT_BYTES)
        Blake3.initHash().update(HexBytes.fromHex(key.hex)).doFinalize(hash)
        return HexBytes.toHex(hash)
    }

    /**
     * Fingerprint suffixes for every claimed member of a group, collision-
     * extended: any two members sharing the same 4-digit prefix both show 6.
     * Deterministic — every device renders identical handles.
     */
    fun fingerprintsFor(state: GroupState): Map<MemberId, String> {
        val full = state.members.values
            .filter { it.identityKey != null }
            .associate { it.id to fingerprint(checkNotNull(it.identityKey)) }
        val shortCounts = full.values.groupingBy { it.take(SHORT_DIGITS) }.eachCount()
        return full.mapValues { (_, print) ->
            val short = print.take(SHORT_DIGITS)
            if ((shortCounts[short] ?: 0) > 1) print.take(EXTENDED_DIGITS) else short
        }
    }

    /** "Priya #a1b2" — for places that need the combined plain-text form. */
    fun display(name: String, fingerprintDigits: String): String = "$name #$fingerprintDigits"

    private const val FINGERPRINT_BYTES = 32
}
