// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.ledger.LedgerTestFixtures.SimDevice
import app.dibs.core.domain.ledger.LedgerTestFixtures.addMember
import app.dibs.core.domain.ledger.LedgerTestFixtures.alice
import app.dibs.core.domain.ledger.LedgerTestFixtures.bob
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceA
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceB
import app.dibs.core.domain.money.CurrencyCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Stage 3b payloads: group metadata and member reactivation. */
class GroupMetaProjectorTest : FunSpec({

    fun createGroup(
        name: String = "Lisbon trip",
        emoji: String? = "🌊",
        colorArgb: Long? = 0xFF8B5000,
        currency: CurrencyCode = CurrencyCode("EUR"),
    ) = OpPayload.CreateGroup(name, emoji, colorArgb, currency)

    test("given a CreateGroup op when folded then the group metadata is available in state") {
        val a = SimDevice(deviceA)
        a.act(createGroup())

        val meta = a.state().meta.shouldNotBeNull()
        meta.name shouldBe "Lisbon trip"
        meta.emoji shouldBe "🌊"
        meta.colorArgb shouldBe 0xFF8B5000
        meta.defaultCurrency shouldBe CurrencyCode("EUR")
    }

    test("given two concurrent CreateGroup ops then the replay-order winner sticks and the loser is quarantined identically everywhere") {
        val a = SimDevice(deviceA)
        val b = SimDevice(deviceB)
        a.act(createGroup(name = "From A"))
        b.act(createGroup(name = "From B"))
        a.receive(b.log.all)
        b.receive(a.log.all)

        listOf(a, b).forEach { device ->
            // Same lamport; deviceA < deviceB, so A's create folds first and wins.
            device.state().meta.shouldNotBeNull().name shouldBe "From A"
            device.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.DuplicateEntity>()
        }
        a.state().canonicalBytes().contentEquals(b.state().canonicalBytes()) shouldBe true
    }

    test("given an EditGroup changing only the name then other metadata fields keep their values") {
        val a = SimDevice(deviceA)
        a.act(createGroup())
        a.act(OpPayload.EditGroup(name = "Lisbon 2026"))

        val meta = a.state().meta.shouldNotBeNull()
        meta.name shouldBe "Lisbon 2026"
        meta.emoji shouldBe "🌊"
        meta.defaultCurrency shouldBe CurrencyCode("EUR")
    }

    test("given an EditGroup clearing the emoji then cleared and untouched are distinguishable") {
        val a = SimDevice(deviceA)
        a.act(createGroup())
        a.act(OpPayload.EditGroup(emoji = EmojiEdit(null)))

        val meta = a.state().meta.shouldNotBeNull()
        meta.emoji shouldBe null
        meta.name shouldBe "Lisbon trip"
    }

    test("given an EditGroup before any CreateGroup then it is quarantined as unknown group") {
        val a = SimDevice(deviceA)
        a.act(OpPayload.EditGroup(name = "ghost"))
        a.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.UnknownGroup>()
    }

    test("given a deactivated member when reactivated then the member is active again with history intact") {
        val a = SimDevice(deviceA)
        a.act(addMember(alice, "Alice"))
        a.act(OpPayload.DeactivateMember(alice))
        a.state().members.getValue(alice).active shouldBe false

        a.act(OpPayload.ReactivateMember(alice))
        a.state().members.getValue(alice).active shouldBe true
        a.state().members.getValue(alice).name shouldBe "Alice"
    }

    test("given a ReactivateMember for an unknown member then it is quarantined") {
        val a = SimDevice(deviceA)
        a.act(OpPayload.ReactivateMember(bob))
        a.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.UnknownMember>()
    }

    test("given group metadata in state then canonical bytes include it and devices agree") {
        val a = SimDevice(deviceA)
        val b = SimDevice(deviceB)
        a.act(createGroup())
        a.act(OpPayload.EditGroup(name = "Renamed"))
        b.receive(a.log.all)

        b.state().canonicalBytes().contentEquals(a.state().canonicalBytes()) shouldBe true
        String(a.state().canonicalBytes(), Charsets.UTF_8).contains("Renamed") shouldBe true
    }

    test("given no group metadata ops then canonical bytes carry no metadata line, keeping M2 vectors valid") {
        val a = SimDevice(deviceA)
        a.act(addMember(alice, "Alice"))
        val rendered = String(a.state().canonicalBytes(), Charsets.UTF_8)
        rendered.contains("\ng|") shouldBe false
    }
})
