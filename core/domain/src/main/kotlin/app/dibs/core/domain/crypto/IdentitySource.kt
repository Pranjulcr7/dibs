// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.crypto

/**
 * Supplies the device owner's identity keypair (SPEC §5.9). An interface so
 * features stay off the key-storage stack: the production implementation
 * derives it from the Keystore-wrapped master key in `:core:data`.
 */
interface IdentitySource {
    suspend fun keyPair(): IdentityKeyPair
}
