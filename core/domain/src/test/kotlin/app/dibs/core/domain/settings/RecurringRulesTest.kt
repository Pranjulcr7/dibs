// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.settings

import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RecurringRulesTest : FunSpec({

    fun rule(id: String, nextDue: Long, period: Int = 30) = RecurringRule(
        id = id,
        groupId = GroupId("g1"),
        templateExpenseId = ExpenseId("e1"),
        periodDays = period,
        nextDueEpochDay = nextDue,
    )

    test("given rules then only those due today or earlier fire") {
        val due = RecurringRules.due(listOf(rule("a", 100), rule("b", 101), rule("c", 99)), todayEpochDay = 100)
        due.map { it.id } shouldBe listOf("a", "c")
    }

    test("given a fired rule then it advances past today, catching up over long gaps without stacking") {
        val advanced = RecurringRules.advance(
            listOf(rule("a", nextDue = 10, period = 7), rule("b", nextDue = 200, period = 7)),
            firedIds = setOf("a"),
            todayEpochDay = 100,
        )
        advanced.first { it.id == "a" }.nextDueEpochDay shouldBe 101
        advanced.first { it.id == "b" }.nextDueEpochDay shouldBe 200
    }

    test("given a rule due exactly today then advancing lands strictly in the future") {
        val advanced = RecurringRules.advance(listOf(rule("a", 100, period = 30)), setOf("a"), 100)
        advanced.single().nextDueEpochDay shouldBe 130
    }
})
