// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.sync

import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.GroupLedger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.sync.VectorClock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class SyncBundlesTest {

    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val deviceA = DeviceId("aa000000-0000-4000-8000-000000000000")
    private val deviceB = DeviceId("bb000000-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")
    private val sender = Identity.keyPairFromMaster(ByteArray(32) { 7 })
    private val groupKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun seededLedger(): GroupLedger {
        val ledger = GroupLedger(group, deviceA)
        ledger.append(OpPayload.CreateGroup("Trip", defaultCurrency = CurrencyCode("EUR")), 1)
        ledger.append(OpPayload.AddMember(alice, "Alice"), 2)
        ledger.append(OpPayload.AddMember(bob, "Bob"), 3)
        ledger.append(
            OpPayload.AddExpense(
                ExpenseId("e1"),
                ExpenseFields(
                    description = "Dinner",
                    currency = CurrencyCode("EUR"),
                    total = Money(3000),
                    paidBy = mapOf(alice to Money(3000)),
                    owedBy = mapOf(alice to Money(1500), bob to Money(1500)),
                    dateEpochDay = 20_100,
                ),
            ),
            4,
        )
        return ledger
    }

    @Test
    fun `given a bundle of ops then the receiver folds to byte-identical state - two devices, no server`() {
        val a = seededLedger()
        val bytes = SyncBundles.encode(group, a.log.all.toList(), groupKey, deviceA, sender)

        val decoded = SyncBundles.decode(bytes, groupKey) as SyncBundles.Result.Decoded
        val b = GroupLedger(group, deviceB)
        b.receive(decoded.ops)

        assertEquals(0, decoded.skippedUnknownOps)
        assertEquals(deviceA, decoded.senderDevice)
        assertEquals(sender.publicKey, decoded.senderKey)
        assertArrayEquals(a.state().canonicalBytes(), b.state().canonicalBytes())
    }

    @Test
    fun `edge 27 - given any flipped byte then the bundle is rejected wholesale`() {
        val bytes = SyncBundles.encode(group, seededLedger().log.all.toList(), groupKey, deviceA, sender)

        // Flip a byte in every region: header, signature, ciphertext.
        for (index in intArrayOf(8, bytes.size / 2, bytes.size - 3)) {
            val tampered = bytes.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            val result = SyncBundles.decode(tampered, groupKey)
            assertTrue(
                "byte $index should reject, got $result",
                result !is SyncBundles.Result.Decoded,
            )
        }
    }

    @Test
    fun `edge 27 - given the wrong group key then decryption fails indistinguishably from tampering`() {
        val bytes = SyncBundles.encode(group, seededLedger().log.all.toList(), groupKey, deviceA, sender)
        val wrongKey = ByteArray(32) { 9 }

        assertEquals(SyncBundles.Result.WrongKeyOrTampered, SyncBundles.decode(bytes, wrongKey))
    }

    @Test
    fun `given a signature by a different key then the bundle reports the forgery`() {
        val bytes = SyncBundles.encode(group, seededLedger().log.all.toList(), groupKey, deviceA, sender)
        // Re-sign the same content with another identity but keep the header's
        // claimed key: verification against the claimed key must fail.
        val forger = Identity.keyPairFromMaster(ByteArray(32) { 13 })
        val headerLength = (bytes[0].toInt() and 0xFF shl 24) or (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or (bytes[3].toInt() and 0xFF)
        val digestInput = bytes.copyOfRange(4, 4 + headerLength) +
            bytes.copyOfRange(4 + headerLength + 64, bytes.size)
        val out = ByteArray(32)
        org.apache.commons.codec.digest.Blake3.initHash().update(digestInput).doFinalize(out)
        val forgedSig = Identity.sign(forger, out)
        val forged = bytes.copyOf()
        forgedSig.copyInto(forged, 4 + headerLength)

        assertEquals(SyncBundles.Result.BadSignature, SyncBundles.decode(forged, groupKey))
    }

    @Test
    fun `edge 28 - given a bundle from a newer major version then it is a hard specific error`() {
        val bytes = SyncBundles.encode(group, seededLedger().log.all.toList(), groupKey, deviceA, sender)
        val headerLength = (bytes[0].toInt() and 0xFF shl 24) or (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or (bytes[3].toInt() and 0xFF)
        val headerText = bytes.decodeToString(4, 4 + headerLength)
        // Rebuild the bundle with major bumped in the header; signature and AEAD
        // are irrelevant — the version gate must fire before either.
        val bumped = headerText.replace("\"major\":1", "\"major\":2").toByteArray(Charsets.UTF_8)
        val rebuilt = ByteArray(4 + bumped.size)
        rebuilt[0] = (bumped.size ushr 24).toByte()
        rebuilt[1] = (bumped.size ushr 16).toByte()
        rebuilt[2] = (bumped.size ushr 8).toByte()
        rebuilt[3] = bumped.size.toByte()
        bumped.copyInto(rebuilt, 4)

        assertEquals(SyncBundles.Result.MajorVersionMismatch(2), SyncBundles.decode(rebuilt, groupKey))
    }

    @Test
    fun `given garbage bytes then decode says not-a-bundle instead of throwing`() {
        assertEquals(SyncBundles.Result.NotABundle, SyncBundles.decode(ByteArray(0), groupKey))
        assertEquals(SyncBundles.Result.NotABundle, SyncBundles.decode(ByteArray(3), groupKey))
        assertEquals(
            SyncBundles.Result.NotABundle,
            SyncBundles.decode("not a bundle at all".toByteArray(), groupKey),
        )
    }

    @Test
    fun `edge 75 - given a sender clock ahead of the ops shipped then the receiver can name the gap`() {
        val a = seededLedger()
        // Ship only a prefix, but the header clock attests to everything A knows.
        val shipped = a.log.all.toList().take(2)
        val bytes = SyncBundles.encode(group, a.log.all.toList(), groupKey, deviceA, sender)
        val decoded = SyncBundles.decode(bytes, groupKey) as SyncBundles.Result.Decoded

        val b = GroupLedger(group, deviceB)
        b.receive(shipped)
        val local = VectorClock.of(b.log.all)

        assertTrue(local.behind(decoded.senderClock).isNotEmpty())
    }

    @Test
    fun `given the plaintext header then peekGroupId routes without any key`() {
        val bytes = SyncBundles.encode(group, seededLedger().log.all.toList(), groupKey, deviceA, sender)
        assertEquals(group, SyncBundles.peekGroupId(bytes))
    }
}
