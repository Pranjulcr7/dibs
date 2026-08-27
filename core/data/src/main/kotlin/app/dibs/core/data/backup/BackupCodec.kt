// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.backup

import com.google.crypto.tink.subtle.XChaCha20Poly1305
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom

/** Everything a `.dibs` file carries (FR-I3): the log, the identity, the local settings. */
@Serializable
data class BackupPayload(
    val version: Int = 1,
    val masterKeyHex: String,
    val settingsJson: String,
    val groups: List<BackupGroup>,
    /** Per-group sync keys (M6) — additive with a default, so v1 backups still read. */
    val groupKeysHex: Map<String, String> = emptyMap(),
)

@Serializable
data class BackupGroup(val groupId: String, val ops: List<BackupOp>)

@Serializable
data class BackupOp(
    val opId: String,
    val groupId: String,
    val lamport: Long,
    val deviceId: String,
    val parentsJson: String,
    val wallClockMillis: Long,
    val payloadJson: String,
)

/**
 * Backup file crypto per SPEC §5.7: Argon2id derives the key from the
 * passphrase, XChaCha20-Poly1305 seals the JSON payload. The header (magic,
 * version, salt, Argon2 parameters) is bound as AEAD associated data, so
 * tampering with *any* byte — parameters included — fails authentication
 * wholesale (edge 59). A lost passphrase is unrecoverable by design (edge 60).
 */
object BackupCodec {

    private val MAGIC = "DIBS".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_VERSION: Byte = 1
    private const val SALT_LENGTH = 16
    private const val KEY_LENGTH = 32
    private const val ARGON2_ITERATIONS = 3
    private const val ARGON2_MEMORY_KB = 32 * 1024
    private const val ARGON2_PARALLELISM = 1
    private const val HEADER_LENGTH = 4 + 1 + SALT_LENGTH + 4 + 4 + 4

    private val json = Json { ignoreUnknownKeys = true }

    fun encrypt(payload: BackupPayload, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val header = header(salt, ARGON2_ITERATIONS, ARGON2_MEMORY_KB, ARGON2_PARALLELISM)
        val key = deriveKey(passphrase, salt, ARGON2_ITERATIONS, ARGON2_MEMORY_KB, ARGON2_PARALLELISM)
        val ciphertext = XChaCha20Poly1305(key)
            .encrypt(json.encodeToString(payload).toByteArray(Charsets.UTF_8), header)
        return header + ciphertext
    }

    @Suppress("ReturnCount") // fail-closed parser: every malformed shape exits with null
    fun decrypt(bytes: ByteArray, passphrase: CharArray): BackupPayload? {
        if (bytes.size <= HEADER_LENGTH) return null
        val header = bytes.copyOfRange(0, HEADER_LENGTH)
        val buffer = ByteBuffer.wrap(header)
        val magic = ByteArray(4).also(buffer::get)
        if (!magic.contentEquals(MAGIC) || buffer.get() != FORMAT_VERSION) return null
        val salt = ByteArray(SALT_LENGTH).also(buffer::get)
        val iterations = buffer.int
        val memoryKb = buffer.int
        val parallelism = buffer.int
        if (iterations !in 1..10 || memoryKb !in 1024..1_048_576 || parallelism !in 1..8) return null

        val key = deriveKey(passphrase, salt, iterations, memoryKb, parallelism)
        val plaintext = try {
            XChaCha20Poly1305(key).decrypt(bytes.copyOfRange(HEADER_LENGTH, bytes.size), header)
        } catch (_: GeneralSecurityException) {
            return null
        }
        return try {
            json.decodeFromString<BackupPayload>(plaintext.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun header(salt: ByteArray, iterations: Int, memoryKb: Int, parallelism: Int): ByteArray =
        ByteBuffer.allocate(HEADER_LENGTH)
            .put(MAGIC)
            .put(FORMAT_VERSION)
            .put(salt)
            .putInt(iterations)
            .putInt(memoryKb)
            .putInt(parallelism)
            .array()

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        memoryKb: Int,
        parallelism: Int,
    ): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withIterations(iterations)
                .withMemoryAsKB(memoryKb)
                .withParallelism(parallelism)
                .build(),
        )
        val key = ByteArray(KEY_LENGTH)
        generator.generateBytes(passphrase, key, 0, KEY_LENGTH)
        return key
    }
}
