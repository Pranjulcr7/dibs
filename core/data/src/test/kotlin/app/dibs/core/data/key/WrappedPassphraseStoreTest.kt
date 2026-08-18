// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.key

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WrappedPassphraseStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Reversible fake so the store's logic is testable without a Keystore. */
    private val xorCipher = object : PassphraseCipher {
        override fun wrap(plaintext: ByteArray) = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun unwrap(wrapped: ByteArray) = wrapped.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private fun store(file: File) = WrappedPassphraseStore(file, xorCipher)

    @Test
    fun `given no stored passphrase when requested then a random 32-byte passphrase is created and persisted wrapped`() {
        val file = File(tmp.root, "db.key")
        val passphrase = store(file).getOrCreatePassphrase()

        assertEquals(32, passphrase.size)
        assertFalse("wrapped file must not contain the plaintext passphrase", file.readBytes().contentEquals(passphrase))
        assertArrayEquals(passphrase, xorCipher.unwrap(file.readBytes()))
    }

    @Test
    fun `given a stored passphrase when requested again then the same passphrase is returned`() {
        val file = File(tmp.root, "db.key")
        val first = store(file).getOrCreatePassphrase()
        val second = store(file).getOrCreatePassphrase()
        assertArrayEquals(first, second)
    }

    @Test
    fun `given two different stores then their passphrases are independent random values`() {
        val a = store(File(tmp.root, "a.key")).getOrCreatePassphrase()
        val b = store(File(tmp.root, "b.key")).getOrCreatePassphrase()
        assertFalse(a.contentEquals(b))
    }
}
