// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.backup

import app.dibs.core.data.db.OpEntity
import app.dibs.core.data.key.WrappedPassphraseStore
import app.dibs.core.data.ledger.LedgerRepository
import app.dibs.core.data.ledger.OpCodec
import app.dibs.core.domain.crypto.HexBytes
import app.dibs.core.domain.ledger.BackupService
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.settings.SettingsStore
import app.dibs.core.domain.settings.UserSettings
import app.dibs.core.domain.sync.GroupKeyStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Assembles and restores `.dibs` backups (FR-I3/I4). Restore is a merge — op
 * sets union by content address, so restoring twice or onto existing data can
 * only add, never duplicate or destroy (edges 55/57). The identity master key
 * is adopted only when this device has none: overwriting a live identity
 * would silently orphan its claims.
 */
class DefaultBackupService(
    private val repository: LedgerRepository,
    private val settingsStore: SettingsStore,
    private val masterKeyStore: WrappedPassphraseStore,
    private val groupKeyStore: GroupKeyStore,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : BackupService {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun createBackup(passphrase: CharArray): ByteArray {
        val settings = settingsStore.settings.first()
        val groupIds = repository.observeGroupIds().first()
        val groups = groupIds.sorted().map { groupId ->
            BackupGroup(
                groupId = groupId.value,
                ops = repository.allOps(groupId).map { op ->
                    val entity = OpCodec.toEntity(op)
                    BackupOp(
                        opId = entity.opId,
                        groupId = entity.groupId,
                        lamport = entity.lamport,
                        deviceId = entity.deviceId,
                        parentsJson = entity.parentsJson,
                        wallClockMillis = entity.wallClockMillis,
                        payloadJson = entity.payloadJson,
                    )
                },
            )
        }
        val payload = BackupPayload(
            masterKeyHex = HexBytes.toHex(masterKeyStore.getOrCreatePassphrase()),
            settingsJson = json.encodeToString(settings),
            groups = groups,
            groupKeysHex = groupKeyStore.all().entries
                .sortedBy { it.key.value }
                .associate { it.key.value to HexBytes.toHex(it.value) },
        )
        val encrypted = BackupCodec.encrypt(payload, passphrase)
        val opCount = groups.sumOf { it.ops.size.toLong() }
        settingsStore.update { it.copy(backedUpOpCount = opCount, lastBackupAtMillis = wallClock()) }
        return encrypted
    }

    override suspend fun restoreBackup(bytes: ByteArray, passphrase: CharArray): BackupService.RestoreResult {
        val payload = BackupCodec.decrypt(bytes, passphrase)
            ?: return BackupService.RestoreResult.WrongPassphraseOrCorrupt

        val identityAdopted = masterKeyStore.adoptIfAbsent(HexBytes.fromHex(payload.masterKeyHex))

        // Group keys adopt-if-absent, mirroring the identity rule: a live key is
        // never overwritten by a backup — restore is additive, local wins.
        payload.groupKeysHex.forEach { (groupId, keyHex) ->
            val id = GroupId(groupId)
            if (groupKeyStore.keyFor(id) == null) {
                groupKeyStore.adopt(id, HexBytes.fromHex(keyHex))
            }
        }

        var opsAdded = 0
        payload.groups.forEach { group ->
            val groupId = GroupId(group.groupId)
            val known = repository.allOps(groupId).map { it.id.value }.toSet()
            val ops = group.ops
                .filterNot { it.opId in known }
                .map {
                    OpCodec.fromEntity(
                        OpEntity(
                            opId = it.opId,
                            groupId = it.groupId,
                            lamport = it.lamport,
                            deviceId = it.deviceId,
                            parentsJson = it.parentsJson,
                            wallClockMillis = it.wallClockMillis,
                            payloadJson = it.payloadJson,
                        ),
                    )
                }
            repository.ingest(groupId, ops)
            opsAdded += ops.size
        }

        // Merge settings: local values win; the backup only fills gaps.
        val backupSettings = try {
            json.decodeFromString<UserSettings>(payload.settingsJson)
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (backupSettings != null) {
            settingsStore.update { local ->
                local.copy(
                    displayName = local.displayName.ifBlank { backupSettings.displayName },
                    selfMembers = backupSettings.selfMembers + local.selfMembers,
                    onboardingComplete = local.onboardingComplete || backupSettings.onboardingComplete,
                )
            }
        }
        return BackupService.RestoreResult.Restored(
            groups = payload.groups.size,
            opsAdded = opsAdded,
            identityAdopted = identityAdopted,
        )
    }
}
