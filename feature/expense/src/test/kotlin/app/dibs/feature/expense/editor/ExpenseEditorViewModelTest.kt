// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.expense.editor

import androidx.lifecycle.SavedStateHandle
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.money.MoneyInput
import app.dibs.core.domain.settings.UserSettings
import app.dibs.core.domain.split.SplitCalculator
import app.dibs.core.domain.split.SplitResult
import app.dibs.core.domain.split.SplitSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class ExpenseEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val groupId = GroupId("11111111-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")
    private val carol = MemberId("0c000000-0000-4000-8000-000000000003")
    private val usd = CurrencyCode("USD")

    private lateinit var ledger: FakeLedger
    private lateinit var settings: FakeSettingsStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ledger = FakeLedger()
        settings = FakeSettingsStore(UserSettings(selfMembers = mapOf(groupId to alice)))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun seedGroup() {
        ledger.append(groupId, OpPayload.CreateGroup("Trip", defaultCurrency = usd))
        ledger.append(groupId, OpPayload.AddMember(alice, "Alice"))
        ledger.append(groupId, OpPayload.AddMember(bob, "Bob"))
        ledger.append(groupId, OpPayload.AddMember(carol, "Carol"))
    }

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle(mapOf("groupId" to groupId.value))) =
        ExpenseEditorViewModel(handle, ledger, settings)

    // -- the requirement: the indicator IS the domain allocator ---------------

    @Test
    fun `given exact amounts under the total then the remaining indicator equals the domain allocator's remaining, always`() = runTest(dispatcher) {
        seedGroup()
        val random = Random(7)
        repeat(300) {
            val total = random.nextLong(1, 1_000_000)
            val assignedA = random.nextLong(0, total + 500) // sometimes over-assigned
            val draft = EditorDraft(
                amountText = MoneyInput.toPlainString(Money(total), usd),
                mode = "exact",
                participants = setOf(alice.value, bob.value),
                exactText = mapOf(alice.value to MoneyInput.toPlainString(Money(assignedA), usd)),
            )
            val preview = previewFor(draft, usd)
            val domain = SplitCalculator.split(
                Money(total),
                SplitSpec.ByExactAmounts(mapOf(alice to Money(assignedA), bob to Money.ZERO)),
            )
            when (domain) {
                is SplitResult.Success -> assertTrue(preview is SplitPreview.Ready)
                is SplitResult.Failure.AmountsDoNotSumToTotal ->
                    assertEquals(domain.remaining, (preview as SplitPreview.RemainingMoney).remaining)
                else -> error("unexpected $domain")
            }
        }
    }

    @Test
    fun `given percentages under 100 then the shortfall shown equals the domain allocator's shortfall`() = runTest(dispatcher) {
        seedGroup()
        val draft = EditorDraft(
            amountText = "10.00",
            mode = "percent",
            participants = setOf(alice.value, bob.value, carol.value),
            percentText = mapOf(alice.value to "33.33", bob.value to "33.33", carol.value to "33.33"),
        )
        val preview = previewFor(draft, usd) as SplitPreview.RemainingPercent
        val domain = SplitCalculator.split(
            Money(1000),
            SplitSpec.ByPercentage(mapOf(alice to 3333L, bob to 3333L, carol to 3333L)),
        ) as SplitResult.Failure.PercentagesDoNotSumTo100
        assertEquals(domain.shortfallBasisPoints, preview.shortfallBasisPoints)
        assertEquals(1L, preview.shortfallBasisPoints)
    }

    @Test
    fun `given ten dollars split equally among three then the preview names the member who absorbed the extra cent`() = runTest(dispatcher) {
        seedGroup()
        val draft = EditorDraft(
            amountText = "10.00",
            mode = "equal",
            participants = setOf(alice.value, bob.value, carol.value),
        )
        val preview = previewFor(draft, usd) as SplitPreview.Ready
        assertEquals(1000L, preview.allocations.sumOf { it.amount.minorUnits })
        assertEquals(listOf(alice), preview.absorbers.map { it.memberId })
        assertEquals(1L, preview.absorbers.single().roundingAdjustment)
    }

    @Test
    fun `given an itemized draft then the total is computed from items plus tax and tip, never typed`() = runTest(dispatcher) {
        seedGroup()
        val draft = EditorDraft(
            mode = "itemized",
            items = listOf(
                ItemDraft(id = "i1", name = "burger", priceText = "10.00", consumers = mapOf(alice.value to 1L)),
                ItemDraft(id = "i2", name = "fries", priceText = "6.00", consumers = mapOf(alice.value to 1L, bob.value to 1L)),
            ),
            taxText = "1.60",
            tipText = "2.40",
        )
        assertEquals(Money(2000), itemizedTotalOf(draft, usd))
        val preview = previewFor(draft, usd) as SplitPreview.Ready
        assertEquals(2000L, preview.allocations.sumOf { it.amount.minorUnits })
    }

    @Test
    fun `given an itemized item with nobody assigned then the preview says so instead of computing`() = runTest(dispatcher) {
        seedGroup()
        val draft = EditorDraft(
            mode = "itemized",
            items = listOf(ItemDraft(id = "i1", priceText = "5.00")),
        )
        assertEquals(
            SplitPreview.Issue.ITEM_UNASSIGNED,
            (previewFor(draft, usd) as SplitPreview.NotReady).issue,
        )
    }

    // -- saving through the ledger -------------------------------------------

    @Test
    fun `given a valid equal split when saved then the folded expense matches the preview allocations exactly`() = runTest(dispatcher) {
        seedGroup()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.updateDraft {
            it.copy(
                amountText = "10.00",
                description = "Dinner",
                mode = "equal",
                participants = setOf(alice.value, bob.value, carol.value),
                category = "food",
            )
        }
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()
        vm.saved.first()

        val expense = ledger.state(groupId).expenses.values.single()
        assertEquals("Dinner", expense.fields.description)
        assertEquals(Money(1000), expense.fields.total)
        assertEquals(Money(1000), expense.fields.paidBy.getValue(alice))
        assertEquals(Money(334), expense.fields.owedBy.getValue(alice))
        assertEquals(Money(333), expense.fields.owedBy.getValue(bob))
        assertEquals("equal", expense.fields.splitMode)
        assertEquals("food", expense.fields.category)
        assertEquals(1000L, expense.fields.owedBy.values.sumOf { it.minorUnits })
    }

    @Test
    fun `given edit mode when only the description changes then the edit op carries only the description`() = runTest(dispatcher) {
        seedGroup()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.updateDraft {
            it.copy(amountText = "9.00", description = "Taxi", mode = "equal", participants = setOf(alice.value, bob.value, carol.value))
        }
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()
        val id = ledger.state(groupId).expenses.keys.single()

        val editVm = viewModel(
            SavedStateHandle(mapOf("groupId" to groupId.value, "expenseId" to id.value)),
        )
        dispatcher.scheduler.advanceUntilIdle()
        // Equal split reopens as equal (D32).
        assertEquals("equal", editVm.uiState.first { it.loaded }.draft.mode)

        editVm.updateDraft { it.copy(description = "Taxi home") }
        editVm.save()
        dispatcher.scheduler.advanceUntilIdle()

        val folded = ledger.state(groupId).expenses.getValue(id)
        assertEquals("Taxi home", folded.fields.description)
        // Amounts untouched: still the original equal allocations.
        assertEquals(Money(300), folded.fields.owedBy.getValue(alice))
        assertEquals(2, folded.history.size)
    }

    @Test
    fun `given a percent expense when reopened then it prefills as exact amounts with the stored truth`() = runTest(dispatcher) {
        seedGroup()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.updateDraft {
            it.copy(
                amountText = "10.00",
                description = "Split",
                mode = "percent",
                participants = setOf(alice.value, bob.value),
                percentText = mapOf(alice.value to "60", bob.value to "40"),
            )
        }
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()
        val id = ledger.state(groupId).expenses.keys.single()

        val editVm = viewModel(SavedStateHandle(mapOf("groupId" to groupId.value, "expenseId" to id.value)))
        dispatcher.scheduler.advanceUntilIdle()
        val draft = editVm.uiState.first { it.loaded }.draft
        assertEquals("exact", draft.mode)
        assertEquals("6.00", draft.exactText[alice.value])
        assertEquals("4.00", draft.exactText[bob.value])
    }

    @Test
    fun `given a process-death draft in SavedStateHandle then the editor restores it verbatim`() = runTest(dispatcher) {
        seedGroup()
        val handle = SavedStateHandle(mapOf("groupId" to groupId.value))
        val vm = viewModel(handle)
        dispatcher.scheduler.advanceUntilIdle()
        vm.updateDraft { it.copy(amountText = "42.00", description = "Draft survives") }

        // New ViewModel over the same handle — as after process death.
        val revived = viewModel(handle)
        dispatcher.scheduler.advanceUntilIdle()
        val draft = revived.uiState.first { it.loaded }.draft
        assertEquals("42.00", draft.amountText)
        assertEquals("Draft survives", draft.description)
    }

    @Test
    fun `given duplicateOf then the editor prefills from the source but saves a brand-new expense`() = runTest(dispatcher) {
        seedGroup()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.updateDraft {
            it.copy(amountText = "5.00", description = "Coffee", mode = "equal", participants = setOf(alice.value, bob.value))
        }
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()
        val originalId = ledger.state(groupId).expenses.keys.single()

        val dupVm = viewModel(SavedStateHandle(mapOf("groupId" to groupId.value, "duplicateOf" to originalId.value)))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Coffee", dupVm.uiState.first { it.loaded }.draft.description)
        dupVm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, ledger.state(groupId).expenses.size)
    }

    @Test
    fun `given delete then the expense is tombstoned, recoverable from trash`() = runTest(dispatcher) {
        seedGroup()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.updateDraft {
            it.copy(amountText = "5.00", description = "Oops", mode = "equal", participants = setOf(alice.value))
        }
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()
        val id = ledger.state(groupId).expenses.keys.single()

        val editVm = viewModel(SavedStateHandle(mapOf("groupId" to groupId.value, "expenseId" to id.value)))
        dispatcher.scheduler.advanceUntilIdle()
        editVm.delete()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(ledger.state(groupId).expenses.getValue(id).deleted)
        assertEquals(1, ledger.state(groupId).trash.size)
    }

    @Test
    fun `given a repeat choice when saved then a device-local recurring rule is created against the new expense`() = runTest(dispatcher) {
        seedGroup()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.updateDraft {
            it.copy(
                amountText = "12.00",
                description = "Rent",
                mode = "equal",
                participants = setOf(alice.value, bob.value),
                dateEpochDay = 20_000,
                repeatDays = 30,
            )
        }
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        val rule = settings.state.value.recurringRules.single()
        assertEquals(30, rule.periodDays)
        assertEquals(20_030L, rule.nextDueEpochDay)
        assertEquals(ledger.state(groupId).expenses.keys.single(), rule.templateExpenseId)
    }

    @Test
    fun `given a blank description or unparsable amount then save is disabled`() = runTest(dispatcher) {
        seedGroup()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.updateDraft { it.copy(amountText = "abc", description = "x", participants = setOf(alice.value)) }
        assertTrue(vm.uiState.first { it.loaded }.preview is SplitPreview.NotReady)
        vm.updateDraft { it.copy(amountText = "5.00", description = " ") }
        assertEquals(false, vm.uiState.first { it.loaded }.canSave)
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, ledger.state(groupId).expenses.size)
        assertNull(ledger.state(groupId).expenses.values.firstOrNull())
    }
}
