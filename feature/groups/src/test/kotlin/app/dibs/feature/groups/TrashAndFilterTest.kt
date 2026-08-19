// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import androidx.lifecycle.SavedStateHandle
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.feature.groups.detail.ExpenseFilter
import app.dibs.feature.groups.trash.TrashViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrashAndFilterTest {

    private val dispatcher = StandardTestDispatcher()
    private val groupId = GroupId("11111111-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")
    private val usd = CurrencyCode("USD")
    private lateinit var ledger: FakeLedger

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ledger = FakeLedger()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fields(desc: String, payer: MemberId, category: String? = null, note: String? = null) = ExpenseFields(
        description = desc,
        currency = usd,
        total = Money(100),
        paidBy = mapOf(payer to Money(100)),
        owedBy = mapOf(payer to Money(100)),
        dateEpochDay = 20_000,
        note = note,
        category = category,
    )

    private suspend fun seed() {
        ledger.append(groupId, OpPayload.AddMember(alice, "Alice"))
        ledger.append(groupId, OpPayload.AddMember(bob, "Bob"))
        ledger.append(groupId, OpPayload.AddExpense(ExpenseId("e1"), fields("Dinner at Luigi's", alice, "food")))
        ledger.append(groupId, OpPayload.AddExpense(ExpenseId("e2"), fields("Taxi", bob, "transport", note = "airport run")))
    }

    @Test
    fun `given text, member, and category filters then only matching expenses remain`() = runTest(dispatcher) {
        seed()
        val expenses = ledger.state(groupId).activeExpenses

        assertEquals(listOf("Dinner at Luigi's"), ExpenseFilter.apply(expenses, "luigi", null, null).map { it.fields.description })
        assertEquals(listOf("Taxi"), ExpenseFilter.apply(expenses, "airport", null, null).map { it.fields.description })
        assertEquals(listOf("Taxi"), ExpenseFilter.apply(expenses, "", bob, null).map { it.fields.description })
        assertEquals(listOf("Dinner at Luigi's"), ExpenseFilter.apply(expenses, "", null, "food").map { it.fields.description })
        assertTrue(ExpenseFilter.apply(expenses, "luigi", bob, null).isEmpty())
        assertEquals(2, ExpenseFilter.apply(expenses, "", null, null).size)
    }

    @Test
    fun `given a deletion then trash shows the days remaining and restore brings the expense back`() = runTest(dispatcher) {
        seed()
        ledger.append(groupId, OpPayload.DeleteExpense(ExpenseId("e1")))
        val deleteWallClock = ledger.state(groupId).expenses
            .getValue(ExpenseId("e1")).history.last().wallClockMillis

        val viewModel = TrashViewModel(SavedStateHandle(mapOf("groupId" to groupId.value)), ledger)
        viewModel.wallClock = { deleteWallClock + 5 * 86_400_000L }

        val rows = viewModel.rows.first { it != null }!!
        assertEquals(1, rows.size)
        assertEquals(25, rows.single().daysLeft)

        viewModel.restore(ExpenseId("e1"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, ledger.state(groupId).expenses.getValue(ExpenseId("e1")).deleted)
    }

    @Test
    fun `given a deletion older than 30 days then the trash no longer offers it`() = runTest(dispatcher) {
        seed()
        ledger.append(groupId, OpPayload.DeleteExpense(ExpenseId("e1")))
        val deleteWallClock = ledger.state(groupId).expenses
            .getValue(ExpenseId("e1")).history.last().wallClockMillis

        val viewModel = TrashViewModel(SavedStateHandle(mapOf("groupId" to groupId.value)), ledger)
        viewModel.wallClock = { deleteWallClock + 31 * 86_400_000L }

        assertTrue(viewModel.rows.first { it != null }!!.isEmpty())
    }
}
