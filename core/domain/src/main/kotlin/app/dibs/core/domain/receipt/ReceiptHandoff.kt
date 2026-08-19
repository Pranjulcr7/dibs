// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.receipt

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The reviewed receipt on its way into the expense editor: scan review ends
 * by handing this (as a JSON nav argument) to a fresh editor prefilled in
 * itemized mode, where item assignment happens with the same UI as manual
 * entry (FR-R5) — one path, not two.
 */
@Serializable
data class ReceiptHandoff(
    val items: List<Item>,
    val taxMinor: Long? = null,
    val tipMinor: Long? = null,
    /** `<fingerprint>.jpg` in app-private storage (FR-E8) — doubles as the edge-47 duplicate key. */
    val receiptRef: String? = null,
) {
    @Serializable
    data class Item(val label: String, val quantity: Int, val amountMinor: Long)

    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun of(draft: ReceiptDraft, receiptRef: String?): ReceiptHandoff = ReceiptHandoff(
            items = draft.items.map { Item(it.label, it.quantity, it.totalMinor) },
            taxMinor = draft.taxMinor,
            tipMinor = draft.tipMinor,
            receiptRef = receiptRef,
        )

        fun decode(text: String): ReceiptHandoff? = try {
            json.decodeFromString<ReceiptHandoff>(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
