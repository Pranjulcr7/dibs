// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

/**
 * Encrypted whole-app backup (FR-I3/I4): the entire op-log, the identity
 * master key, and device-local settings, in one passphrase-encrypted `.dibs`
 * blob. An interface here so feature modules can drive it; `:core:data`
 * implements the crypto and storage.
 */
interface BackupService {

    /** The full encrypted backup file contents for the given passphrase. */
    suspend fun createBackup(passphrase: CharArray): ByteArray

    /** Restore-and-merge (edge 57: merge, never silently replace). */
    suspend fun restoreBackup(bytes: ByteArray, passphrase: CharArray): RestoreResult

    sealed interface RestoreResult {
        data class Restored(
            val groups: Int,
            val opsAdded: Int,
            /** True when this device had no identity and adopted the backup's master key. */
            val identityAdopted: Boolean,
        ) : RestoreResult

        /**
         * AEAD authentication failed: wrong passphrase or a damaged file —
         * cryptographically indistinguishable, and nothing was written
         * (edges 58/59: no partial apply, no lockout).
         */
        data object WrongPassphraseOrCorrupt : RestoreResult
    }
}
