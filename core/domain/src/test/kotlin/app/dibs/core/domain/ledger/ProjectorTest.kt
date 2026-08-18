// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.balance.MemberBalance
import app.dibs.core.domain.ledger.LedgerTestFixtures.SimDevice
import app.dibs.core.domain.ledger.LedgerTestFixtures.addExpense
import app.dibs.core.domain.ledger.LedgerTestFixtures.addMember
import app.dibs.core.domain.ledger.LedgerTestFixtures.alice
import app.dibs.core.domain.ledger.LedgerTestFixtures.bob
import app.dibs.core.domain.ledger.LedgerTestFixtures.carol
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceA
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceB
import app.dibs.core.domain.ledger.LedgerTestFixtures.expenseId
import app.dibs.core.domain.ledger.LedgerTestFixtures.settlementId
import app.dibs.core.domain.ledger.LedgerTestFixtures.usd
import app.dibs.core.domain.money.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ProjectorTest : FunSpec({

    fun deviceWithMembers(): SimDevice {
        val device = SimDevice(deviceA)
        device.act(addMember(alice, "Alice"))
        device.act(addMember(bob, "Bob"))
        return device
    }

    fun syncedPair(): Pair<SimDevice, SimDevice> {
        val a = deviceWithMembers()
        val b = SimDevice(deviceB)
        b.receive(a.log.all)
        return a to b
    }

    test("given members and an expense when folded then balances follow from the expense") {
        val a = deviceWithMembers()
        a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), bob to Money(500))))

        val balances = a.state().balances().getValue(usd)
        balances shouldContainExactly listOf(
            MemberBalance(alice, Money(500)),
            MemberBalance(bob, Money(-500)),
        )
    }

    test("edge 20: given two devices edit different fields of the same expense offline then both edits survive the merge") {
        val (a, b) = syncedPair()
        val expense = a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), bob to Money(500))))
        b.receive(listOf(expense))

        val editA = a.act(OpPayload.EditExpense(expenseId(1), ExpenseEdit(description = "dinner at Luigi's")))
        val editB = b.act(OpPayload.EditExpense(expenseId(1), ExpenseEdit(note = NoteEdit("split the tiramisu"))))
        a.receive(listOf(editB))
        b.receive(listOf(editA))

        listOf(a, b).forEach { device ->
            val folded = device.state().expenses.getValue(expenseId(1))
            folded.fields.description shouldBe "dinner at Luigi's"
            folded.fields.note shouldBe "split the tiramisu"
        }
    }

    test("edge 20: given two devices edit the same field concurrently then the winner is decided by lamport then deviceId, identically everywhere") {
        val (a, b) = syncedPair()
        val expense = a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), bob to Money(500))))
        b.receive(listOf(expense))

        // Same lamport on both branches: the tie must break on deviceId (B > A,
        // so B's edit sorts later in replay order and wins) — on both devices.
        val editA = a.act(OpPayload.EditExpense(expenseId(1), ExpenseEdit(description = "from device A")))
        val editB = b.act(OpPayload.EditExpense(expenseId(1), ExpenseEdit(description = "from device B")))
        editA.lamport shouldBe editB.lamport
        a.receive(listOf(editB))
        b.receive(listOf(editA))

        a.state().expenses.getValue(expenseId(1)).fields.description shouldBe "from device B"
        b.state().expenses.getValue(expenseId(1)).fields.description shouldBe "from device B"
    }

    test("edge 20: given concurrent edits then the history lists both ops with their device ids so the UI can note the other device") {
        val (a, b) = syncedPair()
        val expense = a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), bob to Money(500))))
        b.receive(listOf(expense))
        val editA = a.act(OpPayload.EditExpense(expenseId(1), ExpenseEdit(description = "A")))
        val editB = b.act(OpPayload.EditExpense(expenseId(1), ExpenseEdit(description = "B")))
        a.receive(listOf(editB))

        val history = a.state().expenses.getValue(expenseId(1)).history
        history.map { it.opId }.containsAll(listOf(editA.id, editB.id)) shouldBe true
        a.state().expenses.getValue(expenseId(1)).editingDevices shouldBe setOf(deviceA, deviceB)
    }

    test("edge 21: given one device deletes while another edits concurrently then delete wins and the edit is preserved in history") {
        val (a, b) = syncedPair()
        val expense = a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), bob to Money(500))))
        b.receive(listOf(expense))

        a.act(OpPayload.DeleteExpense(expenseId(1)))
        b.act(OpPayload.EditExpense(expenseId(1), ExpenseEdit(description = "edited while deleted elsewhere")))
        a.receive(b.log.all)
        b.receive(a.log.all)

        listOf(a, b).forEach { device ->
            val folded = device.state().expenses.getValue(expenseId(1))
            folded.deleted shouldBe true
            folded.fields.description shouldBe "edited while deleted elsewhere"
            folded.history.any { it.kind == ExpenseEvent.Kind.DELETED } shouldBe true
            folded.history.any { it.kind == ExpenseEvent.Kind.EDITED } shouldBe true
        }
        // Deleted expenses do not count toward balances.
        (a.state().balances()[usd]?.all { it.net.isZero } ?: true) shouldBe true
    }

    test("edge 21: given a deleted-while-edited expense when restored from trash then it returns with the edits applied") {
        val (a, b) = syncedPair()
        val expense = a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), bob to Money(500))))
        b.receive(listOf(expense))
        a.act(OpPayload.DeleteExpense(expenseId(1)))
        b.act(OpPayload.EditExpense(expenseId(1), ExpenseEdit(description = "kept edit")))
        a.receive(b.log.all)
        b.receive(a.log.all)

        a.act(OpPayload.RestoreExpense(expenseId(1)))
        b.receive(a.log.all)

        listOf(a, b).forEach { device ->
            val folded = device.state().expenses.getValue(expenseId(1))
            folded.deleted shouldBe false
            folded.fields.description shouldBe "kept edit"
        }
    }

    test("edge 25: given an expense op referencing an unknown member then it is quarantined with a reason, never a crash") {
        val a = deviceWithMembers()
        val ghost = carol // never added on this timeline
        a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), ghost to Money(500))))

        val state = a.state()
        state.expenses.containsKey(expenseId(1)) shouldBe false
        state.quarantined.size shouldBe 1
        val reason = state.quarantined.single().reason
        reason.shouldBeInstanceOf<QuarantineReason.UnknownMember>().memberId shouldBe ghost
        // Balances are unaffected by quarantined data.
        state.balances().isEmpty() shouldBe true
    }

    test("edge 25: given an edit referencing an unknown expense then it is quarantined, not applied and not fatal") {
        val a = deviceWithMembers()
        a.act(OpPayload.EditExpense(expenseId(9), ExpenseEdit(description = "ghost")))
        val reason = a.state().quarantined.single().reason
        reason.shouldBeInstanceOf<QuarantineReason.UnknownExpense>().expenseId shouldBe expenseId(9)
    }

    test("given an expense whose payments do not sum to its shares then the op is quarantined as invalid") {
        val a = deviceWithMembers()
        a.act(
            OpPayload.AddExpense(
                expenseId(1),
                ExpenseFields(
                    description = "broken",
                    currency = usd,
                    total = Money(1000),
                    paidBy = mapOf(alice to Money(1000)),
                    owedBy = mapOf(alice to Money(300), bob to Money(300)),
                    dateEpochDay = 20_000,
                ),
            ),
        )
        a.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.InvalidAmounts>()
    }

    test("edge 26: given the same person added on two devices then two member records exist and a merge is suggested, never performed") {
        val a = SimDevice(deviceA)
        val b = SimDevice(deviceB)
        a.act(addMember(alice, "Sam"))
        b.act(addMember(bob, "Sam"))
        a.receive(b.log.all)
        b.receive(a.log.all)

        listOf(a, b).forEach { device ->
            val state = device.state()
            state.members.size shouldBe 2
            DuplicateMembers.candidates(state) shouldContainExactly listOf(alice to bob)
        }
    }

    test("given a rename then it wins by replay order and the name history keeps every step") {
        val a = SimDevice(deviceA)
        a.act(addMember(alice, "Alice"))
        a.act(OpPayload.RenameMember(alice, "Alicia"))
        a.act(OpPayload.RenameMember(alice, "Ali"))

        val member = a.state().members.getValue(alice)
        member.name shouldBe "Ali"
        member.nameHistory.map { it.name } shouldContainExactly listOf("Alice", "Alicia", "Ali")
    }

    test("given a deactivated member then the record remains with active false") {
        val a = deviceWithMembers()
        a.act(OpPayload.DeactivateMember(bob))
        a.state().members.getValue(bob).active shouldBe false
    }

    test("given a second AddExpense reusing an existing expense id then the duplicate is quarantined deterministically") {
        val a = deviceWithMembers()
        a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(1000))))
        a.act(addExpense(expenseId(1), bob, 500, mapOf(bob to Money(500))))

        a.state().expenses.getValue(expenseId(1)).fields.total shouldBe Money(1000)
        a.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.DuplicateEntity>()
    }

    test("given an edit changing the money block then currency, total, payers, and split move as one atomic field") {
        val a = deviceWithMembers()
        a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), bob to Money(500))))
        a.act(
            OpPayload.EditExpense(
                expenseId(1),
                ExpenseEdit(
                    amounts = AmountsEdit(
                        currency = usd,
                        total = Money(2000),
                        paidBy = mapOf(bob to Money(2000)),
                        owedBy = mapOf(alice to Money(1000), bob to Money(1000)),
                    ),
                ),
            ),
        )

        val fields = a.state().expenses.getValue(expenseId(1)).fields
        fields.total shouldBe Money(2000)
        fields.paidBy shouldBe mapOf(bob to Money(2000))
        fields.description shouldBe "expense"
        a.state().balances().getValue(usd) shouldContainExactly listOf(
            MemberBalance(alice, Money(-1000)),
            MemberBalance(bob, Money(1000)),
        )
    }

    test("given an amounts edit whose payments do not sum to its shares then the edit is quarantined and the expense keeps its old amounts") {
        val a = deviceWithMembers()
        a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), bob to Money(500))))
        a.act(
            OpPayload.EditExpense(
                expenseId(1),
                ExpenseEdit(
                    amounts = AmountsEdit(
                        currency = usd,
                        total = Money(2000),
                        paidBy = mapOf(bob to Money(2000)),
                        owedBy = mapOf(alice to Money(100)),
                    ),
                ),
            ),
        )

        a.state().expenses.getValue(expenseId(1)).fields.total shouldBe Money(1000)
        a.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.InvalidAmounts>()
    }

    test("given a settlement between known members then it reduces the pair's net balances") {
        val a = deviceWithMembers()
        a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), bob to Money(500))))
        a.act(LedgerTestFixtures.settle(settlementId(1), bob, alice, 200))

        a.state().balances().getValue(usd) shouldContainExactly listOf(
            MemberBalance(alice, Money(300)),
            MemberBalance(bob, Money(-300)),
        )
    }

    test("given a settlement naming an unknown member then it is quarantined") {
        val a = deviceWithMembers()
        a.act(LedgerTestFixtures.settle(settlementId(1), carol, alice, 200))
        a.state().quarantined.single().reason.shouldBeInstanceOf<QuarantineReason.UnknownMember>()
    }
})
