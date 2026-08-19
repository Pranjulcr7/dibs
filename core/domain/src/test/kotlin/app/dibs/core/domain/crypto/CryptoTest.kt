// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.crypto

import app.dibs.core.domain.model.GroupId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CryptoTest : FunSpec({

    test("given RFC 5869 test case 1 when HKDF-SHA256 derives then the output matches the published vector") {
        val okm = Hkdf.derive(
            ikm = ByteArray(22) { 0x0b },
            info = HexBytes.fromHex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
            salt = HexBytes.fromHex("000102030405060708090a0b0c"),
        )
        HexBytes.toHex(okm) shouldBe
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
    }

    test("given RFC 5869 test case 3 with default salt when derived then the output matches the published vector") {
        val okm = Hkdf.derive(ikm = ByteArray(22) { 0x0b }, info = ByteArray(0), length = 42)
        HexBytes.toHex(okm) shouldBe
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
    }

    test("given the same master key when the identity keypair derives twice then it is identical — the recovery property") {
        val master = ByteArray(32) { 0x42 }
        val first = Identity.keyPairFromMaster(master)
        val second = Identity.keyPairFromMaster(master)
        first.publicKey shouldBe second.publicKey
        HexBytes.toHex(first.privateSeed) shouldBe HexBytes.toHex(second.privateSeed)
    }

    test("given different master keys then identities differ") {
        Identity.keyPairFromMaster(ByteArray(32) { 1 }).publicKey shouldNotBe
            Identity.keyPairFromMaster(ByteArray(32) { 2 }).publicKey
    }

    test("given a signature when verified with the right key and message then it passes, and fails otherwise") {
        val keyPair = Identity.keyPairFromMaster(ByteArray(32) { 7 })
        val other = Identity.keyPairFromMaster(ByteArray(32) { 8 })
        val message = "hello ledger".toByteArray()
        val signature = Identity.sign(keyPair, message)

        Identity.verify(keyPair.publicKey, message, signature) shouldBe true
        Identity.verify(other.publicKey, message, signature) shouldBe false
        Identity.verify(keyPair.publicKey, "tampered".toByteArray(), signature) shouldBe false
        Identity.verify(keyPair.publicKey, message, signature.copyOf(63)) shouldBe false
    }

    test("given one master key when transport keys derive for two groups then they are independent and stable") {
        val master = ByteArray(32) { 0x11 }
        val g1 = GroupId("11111111-0000-4000-8000-000000000000")
        val g2 = GroupId("22222222-0000-4000-8000-000000000000")

        val k1 = Identity.transportKey(master, g1)
        val k2 = Identity.transportKey(master, g2)
        k1.contentEquals(k2) shouldBe false
        k1.contentEquals(Identity.transportKey(master, g1)) shouldBe true
        // Transport keys must not reveal the identity key.
        HexBytes.toHex(k1) shouldNotBe Identity.keyPairFromMaster(master).publicKey.hex
    }

    test("given ed25519 signing then it is deterministic — required for golden vectors") {
        val keyPair = Identity.keyPairFromMaster(ByteArray(32) { 9 })
        val message = "same message".toByteArray()
        HexBytes.toHex(Identity.sign(keyPair, message)) shouldBe HexBytes.toHex(Identity.sign(keyPair, message))
    }
})
