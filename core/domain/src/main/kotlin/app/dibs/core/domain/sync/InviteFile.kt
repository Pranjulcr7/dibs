// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.sync

import app.dibs.core.domain.ledger.InviteLink

/**
 * An invite and the group's data in one file (FR-Y1).
 *
 * Why this exists: an invite link carries the group's *key*, not its
 * contents, so a joiner who only has the link sees an empty group until
 * someone separately sends them an update file — two hand-offs, in the right
 * order, explained to a non-technical person. Putting the link in front of a
 * sealed sync bundle makes joining one action: open the file. The QR path
 * (in person) still carries the link alone, because a bundle does not fit in a
 * QR code.
 *
 * Layout: `"DIBSINV1"` (8 bytes) · link length (2 bytes, big-endian) · link
 * (UTF-8) · bundle bytes to end of file.
 *
 * The link is in the clear inside the file, exactly as it would be in a chat
 * message — the file must travel over a channel the inviter trusts, which the
 * invite screen says. The bundle stays sealed under the key the link carries.
 *
 * Parsing is strict and total: this is attacker-controlled input (edge 76), so
 * anything malformed returns null — never an exception, never a partial result.
 */
class InviteFile(val link: InviteLink, val bundle: ByteArray) {

    companion object {
        private val MAGIC = "DIBSINV1".toByteArray(Charsets.US_ASCII)
        private const val LENGTH_BYTES = 2
        private const val BYTE_MASK = 0xFF
        private const val BITS_PER_BYTE = 8

        /** Same ceiling the link parser enforces; checked before slicing. */
        private const val MAX_LINK_BYTES = 512

        fun encode(link: InviteLink, bundle: ByteArray): ByteArray {
            val linkBytes = link.encode().toByteArray(Charsets.UTF_8)
            require(linkBytes.size <= MAX_LINK_BYTES) { "invite link too long: ${linkBytes.size} bytes" }
            require(bundle.isNotEmpty()) { "an invite file must carry the group's data" }
            val out = ByteArray(MAGIC.size + LENGTH_BYTES + linkBytes.size + bundle.size)
            MAGIC.copyInto(out)
            out[MAGIC.size] = (linkBytes.size ushr BITS_PER_BYTE).toByte()
            out[MAGIC.size + 1] = linkBytes.size.toByte()
            linkBytes.copyInto(out, MAGIC.size + LENGTH_BYTES)
            bundle.copyInto(out, MAGIC.size + LENGTH_BYTES + linkBytes.size)
            return out
        }

        /** A cheap check on the first bytes, for routing an opened file before parsing it. */
        fun looksLikeInviteFile(bytes: ByteArray): Boolean =
            bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

        @Suppress("ReturnCount") // strict parser: every malformed shape exits early with null
        fun decode(bytes: ByteArray): InviteFile? {
            if (!looksLikeInviteFile(bytes) || bytes.size < MAGIC.size + LENGTH_BYTES) return null
            val linkLength = (bytes[MAGIC.size].toInt() and BYTE_MASK shl BITS_PER_BYTE) or
                (bytes[MAGIC.size + 1].toInt() and BYTE_MASK)
            val linkStart = MAGIC.size + LENGTH_BYTES
            val bundleStart = linkStart + linkLength
            if (linkLength == 0 || linkLength > MAX_LINK_BYTES || bytes.size <= bundleStart) return null
            val linkText = bytes.copyOfRange(linkStart, bundleStart).toString(Charsets.UTF_8)
            val link = InviteLink.parse(linkText) ?: return null
            return InviteFile(link, bytes.copyOfRange(bundleStart, bytes.size))
        }
    }
}
