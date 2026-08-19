// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (RFC 5869) over HMAC-SHA256, implemented directly on the JDK's `Mac`.
 *
 * Why hand-rolled: the derivation must be byte-identical on every device
 * forever — the identity keypair and per-group transport keys are derived from
 * it (SPEC §5.9), so a library swap that changed output would silently orphan
 * every user's identity. Thirty lines pinned by the RFC's own test vectors is
 * the safest dependency.
 */
object Hkdf {

    private const val HASH_LEN = 32
    private const val ALGORITHM = "HmacSHA256"

    fun derive(
        ikm: ByteArray,
        info: ByteArray,
        length: Int = HASH_LEN,
        salt: ByteArray = ByteArray(HASH_LEN),
    ): ByteArray {
        require(length in 1..255 * HASH_LEN) { "HKDF output length out of range" }
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val toCopy = minOf(HASH_LEN, length - offset)
            System.arraycopy(previous, 0, out, offset, toCopy)
            offset += toCopy
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance(ALGORITHM).run {
        init(SecretKeySpec(key, ALGORITHM))
        doFinal(data)
    }
}
