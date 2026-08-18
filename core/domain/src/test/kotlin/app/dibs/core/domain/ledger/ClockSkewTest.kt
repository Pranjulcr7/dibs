// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.ledger.LedgerTestFixtures.SimDevice
import app.dibs.core.domain.ledger.LedgerTestFixtures.addMember
import app.dibs.core.domain.ledger.LedgerTestFixtures.alice
import app.dibs.core.domain.ledger.LedgerTestFixtures.bob
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceA
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceB
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** SPEC §6.3 edge case 22: wall clocks are display-only; ordering is lamport. */
class ClockSkewTest : FunSpec({

    val epoch1970 = 0L
    val year2099 = 4_070_908_800_000L
    val year2026 = 1_766_000_000_000L
    val twentyFiveHours = 25 * 60 * 60 * 1000L

    test("edge 22: given a device with its clock set to 1970 then op ordering is unaffected because ordering is lamport, not wall clock") {
        val a = SimDevice(deviceA)
        val b = SimDevice(deviceB)
        // Device A stamps 2026, device B stamps 1970 — B's ops still fold after
        // A's when their lamports say so.
        val first = a.act(addMember(alice, "Alice"), wallClockMillis = year2026)
        b.receive(listOf(first))
        b.act(OpPayload.RenameMember(alice, "Alicia"), wallClockMillis = epoch1970)
        a.receive(b.log.all)

        listOf(a, b).forEach { device ->
            device.state().members.getValue(alice).name shouldBe "Alicia"
        }
    }

    test("edge 22: given a device with its clock set to 2099 then folded state is byte-identical to the same ops with sane clocks ordering-wise") {
        val a = SimDevice(deviceA)
        a.act(addMember(alice, "Alice"), wallClockMillis = year2099)
        a.act(addMember(bob, "Bob"), wallClockMillis = epoch1970)
        val state = a.state()
        state.members.keys.toList() shouldBe listOf(alice, bob)
    }

    test("edge 22: given the newest received op is more than 24 hours ahead of the local clock then a skew warning fires") {
        ClockSkew.isSkewed(localWallClockMillis = year2026, newestOpWallClockMillis = year2026 + twentyFiveHours) shouldBe true
    }

    test("edge 22: given the newest received op is more than 24 hours behind the local clock then a skew warning fires") {
        ClockSkew.isSkewed(localWallClockMillis = year2026, newestOpWallClockMillis = year2026 - twentyFiveHours) shouldBe true
    }

    test("given clocks within 24 hours then no skew warning fires") {
        ClockSkew.isSkewed(localWallClockMillis = year2026, newestOpWallClockMillis = year2026 + 60_000) shouldBe false
    }

    test("given no received ops then no skew warning fires") {
        ClockSkew.isSkewed(localWallClockMillis = year2026, newestOpWallClockMillis = null) shouldBe false
    }

    test("given a log with mixed wall clocks then newestWallClock reports the maximum for the skew check") {
        val a = SimDevice(deviceA)
        a.act(addMember(alice, "Alice"), wallClockMillis = year2026)
        a.act(addMember(bob, "Bob"), wallClockMillis = epoch1970)
        a.log.newestWallClockMillis shouldBe year2026
    }
})
