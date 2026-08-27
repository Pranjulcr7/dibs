// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.sync

import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.GroupLedger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VectorClockTest {

    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val deviceA = DeviceId("aa000000-0000-4000-8000-000000000000")
    private val deviceB = DeviceId("bb000000-0000-4000-8000-000000000000")

    @Test
    fun `given ops from two devices when the clock is taken then each device maps to its highest lamport`() {
        val a = GroupLedger(group, deviceA)
        a.append(OpPayload.CreateGroup("Trip", defaultCurrency = app.dibs.core.domain.money.CurrencyCode("USD")), 1)
        a.append(OpPayload.AddMember(MemberId("0a000000-0000-4000-8000-000000000001"), "Alice"), 2)
        val b = GroupLedger(group, deviceB)
        b.receive(a.log.all)
        b.append(OpPayload.AddMember(MemberId("0b000000-0000-4000-8000-000000000002"), "Bob"), 3)

        val clock = VectorClock.of(b.log.all)

        assertEquals(2L, clock.perDevice[deviceA])
        assertEquals(3L, clock.perDevice[deviceB])
    }

    @Test
    fun `given a clock that has seen everything then covers holds and behind is empty`() {
        val ours = VectorClock(mapOf(deviceA to 5L, deviceB to 3L))
        val theirs = VectorClock(mapOf(deviceA to 5L, deviceB to 2L))

        assertTrue(ours.covers(theirs))
        assertTrue(ours.behind(theirs).isEmpty())
    }

    @Test
    fun `edge 75 - given a sender who knows ops we lack then behind names the device and the distance`() {
        val ours = VectorClock(mapOf(deviceA to 5L))
        val sender = VectorClock(mapOf(deviceA to 5L, deviceB to 4L))

        assertFalse(ours.covers(sender))
        assertEquals(mapOf(deviceB to 4L), ours.behind(sender))
    }

    @Test
    fun `given two clocks when merged then the result is the pointwise maximum and covers both`() {
        val ours = VectorClock(mapOf(deviceA to 5L, deviceB to 1L))
        val theirs = VectorClock(mapOf(deviceA to 2L, deviceB to 4L))

        val merged = ours.merge(theirs)

        assertEquals(VectorClock(mapOf(deviceA to 5L, deviceB to 4L)), merged)
        assertTrue(merged.covers(ours))
        assertTrue(merged.covers(theirs))
    }

    @Test
    fun `given the empty clock then any non-empty clock leaves it behind`() {
        val theirs = VectorClock(mapOf(deviceA to 1L))
        assertFalse(VectorClock.EMPTY.covers(theirs))
        assertTrue(theirs.covers(VectorClock.EMPTY))
    }
}
