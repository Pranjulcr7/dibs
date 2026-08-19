// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.backup

/**
 * Backup passphrase strength meter, enforced at creation (edge 77: a backup
 * file may end up shared publicly, so its passphrase is the only wall).
 * Deliberately a coarse heuristic — length dominates, character variety
 * helps — because precision theater ("87% strong") teaches users nothing.
 * [Level.WEAK] passphrases are refused for backup creation.
 */
object PassphraseStrength {

    enum class Level { WEAK, FAIR, STRONG }

    fun assess(passphrase: CharArray): Level {
        val length = passphrase.size
        if (length < MIN_LENGTH) return Level.WEAK

        var classes = 0
        if (passphrase.any { it.isLowerCase() }) classes++
        if (passphrase.any { it.isUpperCase() }) classes++
        if (passphrase.any { it.isDigit() }) classes++
        if (passphrase.any { !it.isLetterOrDigit() }) classes++
        val distinct = passphrase.toSet().size

        return when {
            // A long passphrase ("correct horse battery staple") is strong even
            // as plain lowercase words — length beats symbol sprinkling.
            length >= LONG_LENGTH && distinct >= LONG_MIN_DISTINCT -> Level.STRONG
            length >= FAIR_STRONG_LENGTH && classes >= STRONG_CLASSES -> Level.STRONG
            distinct < MIN_DISTINCT -> Level.WEAK
            classes >= FAIR_CLASSES || length >= FAIR_STRONG_LENGTH -> Level.FAIR
            else -> Level.WEAK
        }
    }

    private const val MIN_LENGTH = 8
    private const val FAIR_STRONG_LENGTH = 12
    private const val LONG_LENGTH = 16
    private const val MIN_DISTINCT = 4
    private const val LONG_MIN_DISTINCT = 6
    private const val FAIR_CLASSES = 2
    private const val STRONG_CLASSES = 3
}
