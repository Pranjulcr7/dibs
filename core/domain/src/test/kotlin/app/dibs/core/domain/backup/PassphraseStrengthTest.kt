// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.backup

import app.dibs.core.domain.backup.PassphraseStrength.Level
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PassphraseStrengthTest {

    private fun levelOf(s: String) = PassphraseStrength.assess(s.toCharArray())

    @Test
    fun `given a passphrase shorter than eight chars then it is weak regardless of variety`() {
        assertEquals(Level.WEAK, levelOf("aB3!x9Q"))
        assertEquals(Level.WEAK, levelOf(""))
    }

    @Test
    fun `given a repeated-character passphrase then it is weak despite its length`() {
        assertEquals(Level.WEAK, levelOf("aaaaaaaaaaaa"))
        assertEquals(Level.WEAK, levelOf("abababababab"))
    }

    @Test
    fun `given eight lowercase letters then it is weak — one class, short`() {
        assertEquals(Level.WEAK, levelOf("sunshine"))
    }

    @Test
    fun `given a short passphrase with mixed classes then it is fair`() {
        assertEquals(Level.FAIR, levelOf("sunshine7"))
        assertEquals(Level.FAIR, levelOf("Sunshine!"))
    }

    @Test
    fun `given twelve-plus chars with three classes then it is strong`() {
        assertEquals(Level.STRONG, levelOf("Sunshine2024!"))
    }

    @Test
    fun `given a long lowercase word-style passphrase then length alone makes it strong`() {
        assertEquals(Level.STRONG, levelOf("correct horse battery staple"))
    }
}
