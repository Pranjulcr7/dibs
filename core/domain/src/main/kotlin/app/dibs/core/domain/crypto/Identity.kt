// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.crypto

import app.dibs.core.domain.model.GroupId
import kotlinx.serialization.Serializable
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest

/** An Ed25519 public identity key, hex-encoded. The stable cross-group identity (SPEC §5.9). */
@Serializable
@JvmInline
value class IdentityKey(val hex: String) : Comparable<IdentityKey> {
    init {
        require(hex.length == KEY_HEX_LENGTH && hex.all { it in "0123456789abcdef" }) {
            "IdentityKey must be $KEY_HEX_LENGTH lowercase hex chars"
        }
    }

    override fun compareTo(other: IdentityKey): Int = hex.compareTo(other.hex)

    companion object {
        const val KEY_HEX_LENGTH = 64
    }
}

/** The private half stays as the raw seed; it never appears in any op or payload. */
class IdentityKeyPair(
    val publicKey: IdentityKey,
    val privateSeed: ByteArray,
)

/**
 * The two key families of SPEC §5.9, both derived from one master key so a
 * restored master key restores the whole identity:
 *
 * - the identity signing keypair, stable across all groups — its fingerprint
 *   is the user's handle, and it travels only inside encrypted payloads;
 * - per-group transport keys, independent per group, so a relay that sees
 *   transport-level identifiers cannot correlate two groups to one person.
 *
 * Ed25519 comes from `net.i2p.crypto:eddsa` (pure Java): the JDK's Ed25519
 * exists only from Java 15 / Android 13+, and minSdk is 26; the boot-classpath
 * shadowing rule (D19) is satisfied — nothing under `org.apache.commons.codec`.
 */
object Identity {

    private val CURVE = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    /** Deterministic: the same master key always yields the same identity (recovery path, SPEC §5.9). */
    fun keyPairFromMaster(masterKey: ByteArray): IdentityKeyPair {
        val seed = Hkdf.derive(masterKey, "dibs-identity-v1".toByteArray(Charsets.UTF_8))
        val privateSpec = EdDSAPrivateKeySpec(seed, CURVE)
        val publicBytes = EdDSAPublicKey(EdDSAPublicKeySpec(privateSpec.a, CURVE)).abyte
        return IdentityKeyPair(IdentityKey(HexBytes.toHex(publicBytes)), seed)
    }

    /** Per-group relay-facing key material; independent across groups by construction (SPEC §5.9). */
    fun transportKey(masterKey: ByteArray, groupId: GroupId): ByteArray =
        Hkdf.derive(masterKey, ("dibs-transport-v1|" + groupId.value).toByteArray(Charsets.UTF_8))

    fun sign(keyPair: IdentityKeyPair, message: ByteArray): ByteArray {
        val engine = EdDSAEngine(MessageDigest.getInstance(CURVE.hashAlgorithm))
        engine.initSign(EdDSAPrivateKey(EdDSAPrivateKeySpec(keyPair.privateSeed, CURVE)))
        engine.update(message)
        return engine.sign()
    }

    /** Pure and deterministic, so the fold may verify signatures directly (edge 74). */
    fun verify(publicKey: IdentityKey, message: ByteArray, signature: ByteArray): Boolean = try {
        val engine = EdDSAEngine(MessageDigest.getInstance(CURVE.hashAlgorithm))
        engine.initVerify(EdDSAPublicKey(EdDSAPublicKeySpec(HexBytes.fromHex(publicKey.hex), CURVE)))
        engine.update(message)
        engine.verify(signature)
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: java.security.SignatureException) {
        false
    }
}
