// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.crypto.IdentityKey
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.InviteId
import app.dibs.core.domain.model.MemberId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.TreeMap

class HandlesAndLinkTest : FunSpec({

    fun member(id: String, key: IdentityKey?) = MemberState(
        id = MemberId(id),
        name = "n",
        active = true,
        nameHistory = emptyList(),
        identityKey = key,
    )

    fun stateWith(vararg members: MemberState): GroupState {
        val map = TreeMap<MemberId, MemberState>()
        members.forEach { map[it.id] = it }
        return GroupState.empty(GroupId("g1")).copy(members = map)
    }

    test("given a claimed member then the handle is 4 fingerprint digits, stable per key") {
        val key = Identity.keyPairFromMaster(ByteArray(32) { 3 }).publicKey
        val prints = Handles.fingerprintsFor(stateWith(member("m1", key)))
        prints.getValue(MemberId("m1")).length shouldBe Handles.SHORT_DIGITS
        prints.getValue(MemberId("m1")) shouldBe Handles.fingerprint(key).take(4)
    }

    test("given two members whose fingerprints collide on 4 digits then both extend to 6 deterministically") {
        // Masters found once by brute force: both fingerprints start with "8e2c".
        val first = Identity.keyPairFromMaster(ByteArray(32) { 1 }).publicKey
        val collidingMaster = ByteArray(32).also {
            val seed = 52_701
            it[0] = (seed and 0xFF).toByte()
            it[1] = ((seed shr 8) and 0xFF).toByte()
            it[2] = ((seed shr 16) and 0xFF).toByte()
            it[31] = 0x55
        }
        val second = Identity.keyPairFromMaster(collidingMaster).publicKey
        Handles.fingerprint(first).take(4) shouldBe Handles.fingerprint(second).take(4)

        val prints = Handles.fingerprintsFor(stateWith(member("m1", first), member("m2", second)))
        prints.getValue(MemberId("m1")).length shouldBe Handles.EXTENDED_DIGITS
        prints.getValue(MemberId("m2")).length shouldBe Handles.EXTENDED_DIGITS
        prints.getValue(MemberId("m1")) shouldNotBe prints.getValue(MemberId("m2"))
    }

    test("given unclaimed members then they get no fingerprint at all") {
        Handles.fingerprintsFor(stateWith(member("m1", null))).isEmpty() shouldBe true
    }

    test("given the display form then it is name space hash fingerprint") {
        Handles.display("Priya", "a1b2") shouldBe "Priya #a1b2"
    }

    test("given an invite link when encoded and parsed then it round-trips") {
        val link = InviteLink(
            groupId = GroupId("11111111-0000-4000-8000-000000000000"),
            groupKey = ByteArray(32) { it.toByte() },
            inviteId = InviteId("22222222-0000-4000-8000-000000000000"),
            signature = ByteArray(64) { (it * 3).toByte() },
        )
        InviteLink.parse(link.encode()) shouldBe link
    }

    test("given malformed links then parsing rejects wholesale, never partially") {
        val good = InviteLink(
            GroupId("g-1"),
            ByteArray(32),
            InviteId("i-1"),
            ByteArray(64),
        ).encode()

        InviteLink.parse(good.replace("dibs://join", "https://evil")) shouldBe null
        InviteLink.parse(good + "&extra=1") shouldBe null
        InviteLink.parse(good.replace("&i=", "&i=../")) shouldBe null
        InviteLink.parse("dibs://join?g=a&k=short&i=b&sig=c") shouldBe null
        InviteLink.parse("dibs://join?" + "g=a&".repeat(200)) shouldBe null
        InviteLink.parse("") shouldBe null
    }
})
