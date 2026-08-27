// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.scan

import androidx.lifecycle.SavedStateHandle
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.GroupLedger
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.ledger.Op
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.receipt.ReceiptDraft
import app.dibs.core.domain.receipt.ReceiptFingerprint
import app.dibs.core.domain.receipt.ReceiptFlag
import app.dibs.core.domain.receipt.ReceiptFrame
import app.dibs.core.domain.receipt.ReceiptItem
import app.dibs.core.domain.receipt.ReceiptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScanViewModelTest {

    private class FakeLedger : Ledger {
        private val ledgers = LinkedHashMap<GroupId, GroupLedger>()
        private val groupIds = MutableStateFlow<Set<GroupId>>(emptySet())
        private var clock = 1_000_000L
        private val states = LinkedHashMap<GroupId, MutableStateFlow<GroupState?>>()

        private fun ledgerFor(id: GroupId) = ledgers.getOrPut(id) {
            GroupLedger(id, DeviceId("fa000000-0000-4000-8000-000000000000"))
        }

        override fun observeGroupIds(): Flow<Set<GroupId>> = groupIds

        override fun observeState(groupId: GroupId): Flow<GroupState> =
            states.getOrPut(groupId) { MutableStateFlow(ledgerFor(groupId).state()) }.filterNotNull()

        override suspend fun state(groupId: GroupId): GroupState = ledgerFor(groupId).state()

        override suspend fun append(groupId: GroupId, payload: OpPayload): Op {
            clock += 1000
            return ledgerFor(groupId).append(payload, clock)
        }

        override suspend fun ingest(groupId: GroupId, ops: Collection<Op>) {
            ledgerFor(groupId).receive(ops)
        }

        override suspend fun pendingCount(groupId: GroupId): Int = 0

        override suspend fun totalOpCount(): Long = ledgers.values.sumOf { it.log.all.size.toLong() }

        override suspend fun allOps(groupId: GroupId): List<Op> = ledgerFor(groupId).log.all.toList()
    }

    private class FakeParser(var result: ReceiptParser.Result) : ReceiptParser {
        override suspend fun parse(frames: List<ReceiptFrame>): ReceiptParser.Result = result
    }

    private val dispatcher = StandardTestDispatcher()
    private val groupId = GroupId("11111111-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")

    private val cleanDraft = ReceiptDraft(
        items = listOf(ReceiptItem("Latte", 1, 450), ReceiptItem("Bagel", 1, 325)),
        totalMinor = 775,
        gatePassed = true,
    )

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

    private suspend fun seedGroup() {
        ledger.append(groupId, OpPayload.CreateGroup("Trip", defaultCurrency = CurrencyCode("EUR")))
        ledger.append(groupId, OpPayload.AddMember(alice, "Alice"))
    }

    private fun viewModel(parser: ReceiptParser) = ScanViewModel(
        SavedStateHandle(mapOf("groupId" to groupId.value)),
        parser,
        ledger,
    )

    @Test
    fun `given a parsed frame then review opens with the draft and the group currency`() = runTest(dispatcher) {
        seedGroup()
        val vm = viewModel(FakeParser(ReceiptParser.Result.Parsed(cleanDraft)))
        vm.addFrame(byteArrayOf(1))
        vm.parse()
        dispatcher.scheduler.advanceUntilIdle()

        val review = vm.step.value as ScanStep.Review
        assertEquals(cleanDraft, review.draft)
        assertEquals(CurrencyCode("EUR"), review.currency)
        assertFalse(review.looksAlreadyAdded)
    }

    @Test
    fun `edge 34 - given nothing found then capture shows retake advice instead of a dead end`() = runTest(dispatcher) {
        seedGroup()
        val vm = viewModel(FakeParser(ReceiptParser.Result.NothingFound))
        vm.addFrame(byteArrayOf(1))
        vm.parse()
        dispatcher.scheduler.advanceUntilIdle()

        val capture = vm.step.value as ScanStep.Capture
        assertEquals(ScanStep.Capture.Failure.NOTHING_FOUND, capture.parseFailed)
        assertEquals(1, capture.frameCount)
    }

    @Test
    fun `given a review edit fixing a bad amount then the arithmetic gate re-runs and passes`() = runTest(dispatcher) {
        seedGroup()
        val misread = cleanDraft.copy(
            items = listOf(ReceiptItem("Latte", 1, 950), ReceiptItem("Bagel", 1, 325)),
            gatePassed = false,
            flags = setOf(ReceiptFlag.ARITHMETIC_MISMATCH),
        )
        val vm = viewModel(FakeParser(ReceiptParser.Result.Parsed(misread)))
        vm.addFrame(byteArrayOf(1))
        vm.parse()
        dispatcher.scheduler.advanceUntilIdle()

        vm.updateItemAmount(0, 450)
        val review = vm.step.value as ScanStep.Review
        assertEquals(true, review.draft.gatePassed)
        assertTrue(review.draft.flags.isEmpty())
    }

    @Test
    fun `edge 47 - given an expense already carrying this receipt's fingerprint then review warns`() = runTest(dispatcher) {
        seedGroup()
        val fingerprint = ReceiptFingerprint.of(cleanDraft)
        ledger.append(
            groupId,
            OpPayload.AddExpense(
                ExpenseId("e1"),
                ExpenseFields(
                    description = "scanned before",
                    currency = CurrencyCode("EUR"),
                    total = Money(775),
                    paidBy = mapOf(alice to Money(775)),
                    owedBy = mapOf(alice to Money(775)),
                    dateEpochDay = 20_000,
                    receiptRef = "$fingerprint.jpg",
                ),
            ),
        )
        val vm = viewModel(FakeParser(ReceiptParser.Result.Parsed(cleanDraft)))
        vm.addFrame(byteArrayOf(1))
        vm.parse()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue((vm.step.value as ScanStep.Review).looksAlreadyAdded)
    }

    @Test
    fun `given the approved review then the handoff carries the edited items and the stored image name`() = runTest(dispatcher) {
        seedGroup()
        val vm = viewModel(FakeParser(ReceiptParser.Result.Parsed(cleanDraft)))
        vm.addFrame(byteArrayOf(1))
        vm.parse()
        dispatcher.scheduler.advanceUntilIdle()
        vm.updateItemLabel(0, "Flat white")
        vm.setTip(100)

        val handoff = checkNotNull(vm.handoff("abc.jpg"))
        assertEquals("Flat white", handoff.items[0].label)
        assertEquals(100L, handoff.tipMinor)
        assertEquals("abc.jpg", handoff.receiptRef)
        assertEquals(handoff, app.dibs.core.domain.receipt.ReceiptHandoff.decode(handoff.encode()))
    }
}
