// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.sync

import app.dibs.core.domain.ledger.InviteLink
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.InviteId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/** FR-Y1: one file that carries the invite and the group's data, so joining is one action. */
class InviteFileTest : StringSpec({

    val link = InviteLink(
        groupId = GroupId("11111111-0000-4000-8000-000000000000"),
        groupKey = ByteArray(32) { it.toByte() },
        inviteId = InviteId("22222222-0000-4000-8000-000000000000"),
        signature = ByteArray(64) { (it * 3).toByte() },
    )
    val bundle = ByteArray(300) { (it % 251).toByte() }

    "given a link and a bundle when encoded then decoding returns both exactly" {
        val decoded = InviteFile.decode(InviteFile.encode(link, bundle)).shouldNotBeNull()

        decoded.link shouldBe link
        decoded.bundle.contentEquals(bundle) shouldBe true
    }

    "given a file when sniffed then only invite files are recognised" {
        InviteFile.looksLikeInviteFile(InviteFile.encode(link, bundle)) shouldBe true
        InviteFile.looksLikeInviteFile(bundle) shouldBe false
        InviteFile.looksLikeInviteFile(ByteArray(0)) shouldBe false
    }

    "edge 76 - given a truncated file at every length then decoding returns null and never throws" {
        val bytes = InviteFile.encode(link, bundle)
        // Everything up to and including the link is required; the bundle must be non-empty.
        val firstBundleByte = bytes.size - bundle.size
        for (length in 0..firstBundleByte) {
            InviteFile.decode(bytes.copyOf(length)).shouldBeNull()
        }
        InviteFile.decode(bytes.copyOf(firstBundleByte + 1)).shouldNotBeNull()
    }

    "edge 76 - given a wrong magic, an oversized link length, or a doctored link then decoding returns null" {
        val bytes = InviteFile.encode(link, bundle)

        InviteFile.decode(bytes.copyOf().also { it[0] = 'X'.code.toByte() }).shouldBeNull()
        // Declared link length far past the cap: must be rejected before any allocation or slice.
        InviteFile.decode(
            bytes.copyOf().also {
                it[8] = 0x7F
                it[9] = 0x7F
            },
        ).shouldBeNull()
        // A link that no longer parses strictly (scheme byte flipped) sinks the whole file.
        InviteFile.decode(bytes.copyOf().also { it[10] = 'x'.code.toByte() }).shouldBeNull()
    }

    "edge 76 - given arbitrary bytes then decoding never throws" {
        checkAll(2_000, Arb.byteArray(Arb.int(0, 700), Arb.byte())) { garbage ->
            InviteFile.decode(garbage)
        }
    }

    "edge 76 - given a valid file with random bytes flipped then decoding never throws" {
        val bytes = InviteFile.encode(link, bundle)
        checkAll(2_000, Arb.int(0, bytes.size - 1), Arb.byte()) { index, value ->
            InviteFile.decode(bytes.copyOf().also { it[index] = value })
        }
    }
})
