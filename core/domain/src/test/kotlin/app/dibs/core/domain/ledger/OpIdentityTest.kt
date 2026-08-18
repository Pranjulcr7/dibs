// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.ledger.LedgerTestFixtures.alice
import app.dibs.core.domain.ledger.LedgerTestFixtures.bob
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceA
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceB
import app.dibs.core.domain.ledger.LedgerTestFixtures.expenseId
import app.dibs.core.domain.ledger.LedgerTestFixtures.group
import app.dibs.core.domain.money.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

class OpIdentityTest : FunSpec({

    val payload = LedgerTestFixtures.addExpense(
        expenseId(1),
        alice,
        1000,
        owedBy = mapOf(alice to Money(500), bob to Money(500)),
    )

    fun create(
        p: OpPayload = payload,
        lamport: Long = 1,
        device: DeviceId = deviceA,
        parents: Set<OpId> = emptySet(),
        wallClock: Long = 42,
    ) = OpFactory.create(group, device, lamport, parents, wallClock, p)

    test("given the same logical op when created twice then the content-addressed id is identical") {
        create().id shouldBe create().id
    }

    test("given map entries in different insertion orders when hashed then the id is identical") {
        val shuffled = LedgerTestFixtures.addExpense(
            expenseId(1),
            alice,
            1000,
            owedBy = linkedMapOf(bob to Money(500), alice to Money(500)),
        )
        create(p = shuffled).id shouldBe create().id
    }

    test("given parent sets in different iteration orders when hashed then the id is identical") {
        val p1 = OpId("a".repeat(64))
        val p2 = OpId("b".repeat(64))
        create(parents = linkedSetOf(p1, p2)).id shouldBe create(parents = linkedSetOf(p2, p1)).id
    }

    test("given any differing header field when hashed then the id differs") {
        val base = create().id
        create(lamport = 2).id shouldNotBe base
        create(device = deviceB).id shouldNotBe base
        create(wallClock = 43).id shouldNotBe base
        create(parents = setOf(OpId("c".repeat(64)))).id shouldNotBe base
    }

    test("given a repeated identical edit when hashed then ids differ because the header is part of the hash") {
        // Rename A -> B -> A: the two "set description to A" edits are byte-identical
        // payloads; if only the payload were hashed the second would dedup away and
        // the revert would be impossible.
        val edit = OpPayload.EditExpense(expenseId(1), ExpenseEdit(description = "A"))
        val first = create(p = edit, lamport = 1)
        val second = create(p = edit, lamport = 3, parents = setOf(first.id))
        second.id shouldNotBe first.id
    }

    test("given differing payload content when hashed then the id differs") {
        val other = LedgerTestFixtures.addExpense(
            expenseId(1),
            alice,
            1000,
            owedBy = mapOf(alice to Money(501), bob to Money(499)),
        )
        create(p = other).id shouldNotBe create().id
    }

    test("given an op id when rendered then it is 64 lowercase hex characters") {
        create().id.value shouldMatch Regex("[0-9a-f]{64}")
    }
})
