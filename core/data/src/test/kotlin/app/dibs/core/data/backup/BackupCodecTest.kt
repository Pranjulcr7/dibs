// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val payload = BackupPayload(
        masterKeyHex = "aa".repeat(32),
        settingsJson = """{"displayName":"Priya"}""",
        groups = listOf(
            BackupGroup(
                groupId = "g1",
                ops = listOf(
                    BackupOp(
                        opId = "op1",
                        groupId = "g1",
                        lamport = 1,
                        deviceId = "d1",
                        parentsJson = "[]",
                        wallClockMillis = 42,
                        payloadJson = """{"type":"add_member"}""",
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `given a passphrase then encrypt-decrypt round-trips the payload exactly`() {
        val encrypted = BackupCodec.encrypt(payload, "correct horse battery".toCharArray())
        val decrypted = BackupCodec.decrypt(encrypted, "correct horse battery".toCharArray())
        assertEquals(payload, decrypted)
    }

    @Test
    fun `given the wrong passphrase then decryption fails with null and nothing else`() {
        val encrypted = BackupCodec.encrypt(payload, "right".toCharArray())
        assertNull(BackupCodec.decrypt(encrypted, "wrong".toCharArray()))
    }

    @Test
    fun `given any single flipped byte then AEAD rejects the file wholesale`() {
        val encrypted = BackupCodec.encrypt(payload, "pw".toCharArray())
        for (index in intArrayOf(0, 10, encrypted.size / 2, encrypted.size - 1)) {
            val tampered = encrypted.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertNull("flip at $index must fail", BackupCodec.decrypt(tampered, "pw".toCharArray()))
        }
    }

    @Test
    fun `given two backups of the same payload then salts differ so ciphertexts differ`() {
        val first = BackupCodec.encrypt(payload, "pw".toCharArray())
        val second = BackupCodec.encrypt(payload, "pw".toCharArray())
        assertTrue(!first.contentEquals(second))
        // But both decrypt to the same payload.
        assertArrayEquals(
            arrayOf(payload, payload),
            arrayOf(
                BackupCodec.decrypt(first, "pw".toCharArray()),
                BackupCodec.decrypt(second, "pw".toCharArray()),
            ),
        )
    }

    @Test
    fun `given garbage or truncated input then decode fails cleanly`() {
        assertNull(BackupCodec.decrypt(ByteArray(0), "pw".toCharArray()))
        assertNull(BackupCodec.decrypt(ByteArray(10) { 1 }, "pw".toCharArray()))
        assertNull(BackupCodec.decrypt("not a backup".toByteArray(), "pw".toCharArray()))
    }
}
