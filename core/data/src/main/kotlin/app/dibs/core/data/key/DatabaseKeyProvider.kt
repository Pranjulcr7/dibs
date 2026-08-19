// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.key

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Supplies the SQLCipher passphrase for the database (NFR-11). */
interface DatabaseKeyProvider {
    fun getOrCreatePassphrase(): ByteArray
}

/**
 * Wraps and unwraps the database passphrase. Separated from the store so the
 * store's file logic is unit-testable on the JVM — the real implementation is
 * an Android Keystore AES key, which no JVM test environment provides.
 */
interface PassphraseCipher {
    fun wrap(plaintext: ByteArray): ByteArray

    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * Persists a random 256-bit database passphrase, wrapped by a [PassphraseCipher],
 * in an app-private file.
 *
 * Why a wrapped random passphrase instead of using a Keystore key directly:
 * SQLCipher needs raw key bytes, and Android Keystore keys are by design not
 * extractable — so the extractable secret is random, and the Keystore only ever
 * wraps it. Stealing the file without the device's Keystore yields ciphertext.
 */
class WrappedPassphraseStore(
    private val file: File,
    private val cipher: PassphraseCipher,
) : DatabaseKeyProvider {

    override fun getOrCreatePassphrase(): ByteArray {
        if (file.isFile) {
            return cipher.unwrap(file.readBytes())
        }
        val passphrase = ByteArray(PASSPHRASE_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        file.parentFile?.mkdirs()
        file.writeBytes(cipher.wrap(passphrase))
        return passphrase
    }

    /**
     * Adopts [secret] only when none exists yet — backup restore must never
     * overwrite a live identity's master key (it would orphan its claims).
     * Returns true when adopted.
     */
    fun adoptIfAbsent(secret: ByteArray): Boolean {
        if (file.isFile) return false
        file.parentFile?.mkdirs()
        file.writeBytes(cipher.wrap(secret))
        return true
    }

    private companion object {
        const val PASSPHRASE_LENGTH_BYTES = 32
    }
}

/**
 * The production [PassphraseCipher]: AES-256-GCM with a key that lives in the
 * Android Keystore (hardware-backed where the device has a TEE; the platform
 * falls back to its software Keystore otherwise, which SPEC §5.7 accepts as the
 * documented fallback). The wrapped file layout is `[12-byte IV][ciphertext]`.
 */
class AndroidKeystoreCipher : PassphraseCipher {

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = wrapped.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = wrapped.copyOfRange(IV_LENGTH_BYTES, wrapped.size)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dibs-db-passphrase-wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
