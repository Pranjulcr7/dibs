// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.ledger.LedgerTestFixtures.SimDevice
import app.dibs.core.domain.ledger.LedgerTestFixtures.addMember
import app.dibs.core.domain.ledger.LedgerTestFixtures.alice
import app.dibs.core.domain.ledger.LedgerTestFixtures.bob
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceA
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceB
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class OpLogTest : FunSpec({

    test("edge 23: given a duplicate op received then it is a no-op by content-addressed identity") {
        val a = SimDevice(deviceA)
        val op = a.act(addMember(alice, "Alice"))
        val b = SimDevice(deviceB)

        b.receive(listOf(op))
        val before = b.state().canonicalBytes()
        b.receive(listOf(op))
        b.receive(listOf(op, op, op))

        b.log.size shouldBe 1
        b.state().canonicalBytes().contentEquals(before) shouldBe true
    }

    test("edge 24: given ops arriving out of causal order then children buffer as pending until parents arrive") {
        val a = SimDevice(deviceA)
        val parent = a.act(addMember(alice, "Alice"))
        val child = a.act(addMember(bob, "Bob"))

        val b = SimDevice(deviceB)
        b.receive(listOf(child))

        // Surfaced as "syncing", not applied and not lost.
        b.log.pending.map { it.id } shouldContainExactly listOf(child.id)
        b.state().members.isEmpty() shouldBe true

        b.receive(listOf(parent))
        b.log.pending.shouldBeEmpty()
        b.state().members.keys shouldContainExactly listOf(alice, bob)
    }

    test("given a grandchild whose parent is itself pending then the whole chain stays pending until complete") {
        val a = SimDevice(deviceA)
        val gen1 = a.act(addMember(alice, "Alice"))
        val gen2 = a.act(addMember(bob, "Bob"))
        val gen3 = a.act(OpPayload.RenameMember(bob, "Bobby"))

        val b = SimDevice(deviceB)
        b.receive(listOf(gen3, gen2))
        b.log.pending.map { it.id }.toSet() shouldBe setOf(gen2.id, gen3.id)
        b.state().members.isEmpty() shouldBe true

        b.receive(listOf(gen1))
        b.log.pending.shouldBeEmpty()
        b.state().members.getValue(bob).name shouldBe "Bobby"
    }

    test("given merged remote ops then the local lamport clock advances past everything seen") {
        val a = SimDevice(deviceA)
        val b = SimDevice(deviceB)
        val op1 = a.act(addMember(alice, "Alice"))
        val op2 = a.act(addMember(bob, "Bob"))

        b.receive(listOf(op1, op2))
        val next = b.act(OpPayload.RenameMember(alice, "Alicia"))

        (next.lamport > op2.lamport) shouldBe true
    }

    test("given a new local op then its parents are the current heads and later ops chain onto it") {
        val a = SimDevice(deviceA)
        val first = a.act(addMember(alice, "Alice"))
        val second = a.act(addMember(bob, "Bob"))

        first.parents.shouldBeEmpty()
        second.parents shouldBe setOf(first.id)
    }

    test("given two devices that acted concurrently then a merging device's next op has both branch heads as parents") {
        val a = SimDevice(deviceA)
        val b = SimDevice(deviceB)
        val fromA = a.act(addMember(alice, "Alice"))
        val fromB = b.act(addMember(bob, "Bob"))

        a.receive(listOf(fromB))
        val merge = a.act(OpPayload.RenameMember(alice, "Alicia"))

        merge.parents shouldBe setOf(fromA.id, fromB.id)
    }
})
