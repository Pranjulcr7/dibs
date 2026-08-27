// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.sync

import app.dibs.core.domain.model.GroupId

/**
 * Holds each group's 256-bit symmetric key (SPEC §5.7): created lazily when a
 * group first needs one (its first invite), adopted from a join link, sealing
 * every sync bundle. The key travels only inside the QR/`dibs://` invite and
 * encrypted backups — never over any future relay (D41).
 *
 * Implementations persist inside the already-encrypted local store; the
 * domain only cares that a group has exactly one key, ever: [adopt] must
 * refuse — loudly — a key that differs from one already held, because two
 * keys for one group means someone is being fed a different group.
 */
interface GroupKeyStore {

    /** The group's key, or null if this device has never held one for it. */
    suspend fun keyFor(groupId: GroupId): ByteArray?

    /** Returns the existing key or creates, persists, and returns a fresh 256-bit one. */
    suspend fun getOrCreate(groupId: GroupId): ByteArray

    /**
     * Stores a key received in a join link or restored from a backup.
     * No-op when the same key is already held; throws [IllegalStateException]
     * when a *different* key is — that conflict must surface, never resolve
     * silently.
     */
    suspend fun adopt(groupId: GroupId, key: ByteArray)

    /** Every held key, for the encrypted backup (restore re-[adopt]s them). */
    suspend fun all(): Map<GroupId, ByteArray>
}
