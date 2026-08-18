// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.ledger

import app.dibs.core.domain.ledger.DeviceId
import java.io.File
import java.util.UUID

/**
 * The installation's stable [DeviceId], created once and persisted.
 *
 * Why a random UUID and not a hardware identifier: hardware ids are restricted,
 * privacy-hostile, and unnecessary — the id only needs to be unique among the
 * few devices in a user's groups and stable across app launches. Randomness is
 * fine *here* because device identity is generated exactly once; it never
 * participates in any computation that must be reproducible.
 */
class DeviceIdProvider(private val file: File) {

    fun getOrCreate(): DeviceId {
        if (file.isFile) {
            val existing = file.readText().trim()
            if (existing.isNotEmpty()) return DeviceId(existing)
        }
        val id = UUID.randomUUID().toString()
        file.parentFile?.mkdirs()
        file.writeText(id)
        return DeviceId(id)
    }
}
