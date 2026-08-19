// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.InviteId
import java.util.Base64

/**
 * The `dibs://join` deep link (SPEC §5.9):
 *
 * ```
 * dibs://join?g=<groupId>&k=<groupKey>&i=<inviteId>&sig=<inviterSignature>
 * ```
 *
 * Group key and signature are base64url without padding. Parsing is strict
 * with hard size caps — deep links are attacker-controlled input (edge 76):
 * unknown parameters, oversized values, or malformed encodings return null,
 * never a partially-parsed link.
 */
data class InviteLink(
    val groupId: GroupId,
    val groupKey: ByteArray,
    val inviteId: InviteId,
    val signature: ByteArray,
) {

    fun encode(): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "dibs://join" +
            "?g=${groupId.value}" +
            "&k=${encoder.encodeToString(groupKey)}" +
            "&i=${inviteId.value}" +
            "&sig=${encoder.encodeToString(signature)}"
    }

    override fun equals(other: Any?): Boolean = other is InviteLink &&
        groupId == other.groupId &&
        groupKey.contentEquals(other.groupKey) &&
        inviteId == other.inviteId &&
        signature.contentEquals(other.signature)

    override fun hashCode(): Int = groupId.hashCode() * 31 + inviteId.hashCode()

    companion object {
        private const val PREFIX = "dibs://join?"
        private const val MAX_LINK_LENGTH = 512
        private const val MAX_ID_LENGTH = 64
        private const val GROUP_KEY_BYTES = 32
        private const val SIGNATURE_BYTES = 64
        private val ID_PATTERN = Regex("[A-Za-z0-9-]{1,$MAX_ID_LENGTH}")

        @Suppress("ReturnCount") // strict parser: every malformed shape exits early with null
        fun parse(raw: String): InviteLink? {
            if (raw.length > MAX_LINK_LENGTH || !raw.startsWith(PREFIX)) return null
            val params = HashMap<String, String>()
            for (pair in raw.removePrefix(PREFIX).split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) return null
                val key = pair.substring(0, eq)
                // Unknown or repeated parameters: reject wholesale.
                if (key !in setOf("g", "k", "i", "sig") || params.containsKey(key)) return null
                params[key] = pair.substring(eq + 1)
            }
            val group = params["g"]?.takeIf { it.matches(ID_PATTERN) } ?: return null
            val invite = params["i"]?.takeIf { it.matches(ID_PATTERN) } ?: return null
            val key = decodeBase64Url(params["k"] ?: return null, GROUP_KEY_BYTES) ?: return null
            val signature = decodeBase64Url(params["sig"] ?: return null, SIGNATURE_BYTES) ?: return null
            return InviteLink(GroupId(group), key, InviteId(invite), signature)
        }

        private fun decodeBase64Url(value: String, expectedBytes: Int): ByteArray? = try {
            Base64.getUrlDecoder().decode(value).takeIf { it.size == expectedBytes }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
