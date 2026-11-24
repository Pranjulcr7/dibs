// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.dibs.core.data.db.DibsDatabase
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.ExpenseEdit
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.GroupLedger
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerRepositoryTest {

    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val localDevice = DeviceId("aa000000-0000-4000-8000-000000000000")
    private val remoteDevice = DeviceId("bb000000-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")

    private lateinit var db: DibsDatabase
    private lateinit var repository: LedgerRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DibsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LedgerRepository(db, localDevice)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun expensePayload(id: ExpenseId, totalMinor: Long) = OpPayload.AddExpense(
        expenseId = id,
        expense = ExpenseFields(
            description = "test",
            currency = CurrencyCode("USD"),
            total = Money(totalMinor),
            paidBy = mapOf(alice to Money(totalMinor)),
            owedBy = mapOf(alice to Money(totalMinor / 2), bob to Money(totalMinor - totalMinor / 2)),
            dateEpochDay = 20_000,
        ),
    )

    @Test
    fun `given local appends when persisted then lamport and parents chain like the domain ledger`() = runTest {
        val first = repository.append(group, OpPayload.AddMember(alice, "Alice"), wallClockMillis = 1)
        val second = repository.append(group, OpPayload.AddMember(bob, "Bob"), wallClockMillis = 2)

        assertEquals(1, first.lamport)
        assertEquals(2, second.lamport)
        assertEquals(emptySet<Any>(), first.parents)
        assertEquals(setOf(first.id), second.parents)
        assertEquals(2, db.opDao().countForGroup(group.value))
    }

    @Test
    fun `given ingested remote ops when folded then repository state matches a pure domain fold of the same ops`() = runTest {
        val remote = GroupLedger(group, remoteDevice)
        remote.append(OpPayload.AddMember(alice, "Alice"), 1)
        remote.append(OpPayload.AddMember(bob, "Bob"), 2)
        remote.append(expensePayload(ExpenseId("e1"), 1000), 3)

        repository.ingest(group, remote.log.all)

        assertArrayEquals(remote.state().canonicalBytes(), repository.state(group).canonicalBytes())
    }

    @Test
    fun `given duplicate ingest when folded then nothing changes`() = runTest {
        val remote = GroupLedger(group, remoteDevice)
        remote.append(OpPayload.AddMember(alice, "Alice"), 1)
        repository.ingest(group, remote.log.all)
        val before = repository.state(group).canonicalBytes()

        repository.ingest(group, remote.log.all)
        repository.ingest(group, remote.log.all)

        assertArrayEquals(before, repository.state(group).canonicalBytes())
        assertEquals(1, db.opDao().countForGroup(group.value))
    }

    @Test
    fun `given ops arriving in causal order then the fold is incremental, and an earlier-sorting late arrival forces a full refold`() = runTest {
        repository.append(group, OpPayload.AddMember(alice, "Alice"), 1)
        repository.append(group, OpPayload.AddMember(bob, "Bob"), 2)
        val incrementalBefore = repository.diagnostics.incrementalFolds
        repository.append(group, expensePayload(ExpenseId("e1"), 1000), 3)
        assertEquals(incrementalBefore + 1, repository.diagnostics.incrementalFolds)

        // A remote op with lamport 1 sorts before everything local: suffix fold impossible.
        val remote = GroupLedger(group, remoteDevice)
        val early = remote.append(OpPayload.AddMember(MemberId("0c000000-0000-4000-8000-000000000003"), "Carol"), 1)
        val fullBefore = repository.diagnostics.fullRefolds
        repository.ingest(group, listOf(early))
        assertEquals(fullBefore + 1, repository.diagnostics.fullRefolds)

        // Either path must land on the same state as a from-scratch fold.
        val reference = GroupLedger(group, remoteDevice)
        reference.receive(repository.allOps(group))
        assertArrayEquals(reference.state().canonicalBytes(), repository.state(group).canonicalBytes())
    }

    @Test
    fun `given a batch appendAll then ops chain like sequential appends and the projection is written exactly once`() = runTest {
        val payloads = buildList {
            add(OpPayload.AddMember(alice, "Alice"))
            add(OpPayload.AddMember(bob, "Bob"))
            repeat(200) { add(expensePayload(ExpenseId("e$it"), (it + 1) * 100L)) }
        }

        val writesBefore = repository.diagnostics.projectionWrites
        val ops = repository.appendAll(group, payloads)

        // The whole point of the batch: one projection rewrite, not one per op (T7).
        assertEquals(writesBefore + 1, repository.diagnostics.projectionWrites)
        assertEquals(payloads.size, ops.size)
        assertEquals(payloads.size.toLong(), db.opDao().countForGroup(group.value).toLong())
        ops.zipWithNext { previous, next ->
            assertEquals(previous.lamport + 1, next.lamport)
            assertEquals(setOf(previous.id), next.parents)
        }
        assertEquals(200, repository.state(group).activeExpenses.size)

        // Identical fold to a from-scratch reference over the same ops.
        val reference = GroupLedger(group, localDevice)
        reference.receive(ops)
        assertArrayEquals(reference.state().canonicalBytes(), repository.state(group).canonicalBytes())

        // And the persisted checkpoint round-trips.
        val reopened = LedgerRepository(db, localDevice)
        assertArrayEquals(repository.state(group).canonicalBytes(), reopened.state(group).canonicalBytes())
    }

    @Test
    fun `given a reopened repository with a fresh snapshot then state restores without touching the op rows`() = runTest {
        repository.append(group, OpPayload.AddMember(alice, "Alice"), 1)
        repository.append(group, OpPayload.AddMember(bob, "Bob"), 2)
        repository.append(group, expensePayload(ExpenseId("e1"), 1000), 3)
        val expected = repository.state(group).canonicalBytes()

        val reopened = LedgerRepository(db, localDevice)
        val restored = reopened.state(group)

        assertEquals(1, reopened.diagnostics.snapshotRestores)
        assertEquals(0, reopened.diagnostics.fullRefolds)
        assertArrayEquals(expected, restored.canonicalBytes())
    }

    @Test
    fun `given a snapshot-restored group then the first append loads the log and chains lamport and parents correctly`() = runTest {
        val last = repository.append(group, OpPayload.AddMember(alice, "Alice"), 1)
            .let { repository.append(group, OpPayload.AddMember(bob, "Bob"), 2) }

        val reopened = LedgerRepository(db, localDevice)
        reopened.state(group) // snapshot restore, no log
        assertEquals(1, reopened.diagnostics.snapshotRestores)
        val op = reopened.append(group, expensePayload(ExpenseId("e1"), 500), 3)

        assertEquals(last.lamport + 1, op.lamport)
        assertEquals(setOf(last.id), op.parents)
        // The appended state must equal a from-scratch domain fold of all ops.
        val reference = GroupLedger(group, localDevice)
        reference.receive(reopened.allOps(group))
        assertArrayEquals(reference.state().canonicalBytes(), reopened.state(group).canonicalBytes())
    }

    @Test
    fun `given a snapshot-restored group then state, op count, and pending count never decode the op log`() = runTest {
        repository.append(group, OpPayload.AddMember(alice, "Alice"), 1)
        repository.append(group, expensePayload(ExpenseId("e1"), 1000), 2)
        // One op whose parent never arrived: stored, but pending (edge 24).
        val remote = GroupLedger(group, remoteDevice)
        remote.append(OpPayload.AddMember(bob, "Bob"), 3)
        val orphan = remote.append(OpPayload.RenameMember(bob, "Bobby"), 4)
        repository.ingest(group, listOf(orphan))

        val reopened = LedgerRepository(db, localDevice)
        reopened.state(group)

        assertEquals(3L, reopened.opCount(group))
        assertEquals(1, reopened.pendingCount(group))
        assertEquals(1, reopened.diagnostics.snapshotRestores)
        // The whole point of D40: a status line costs counts, not an op decode.
        assertEquals(0, reopened.diagnostics.logLoads)
        // And the counts agree with what loading the log would have said.
        assertEquals(3, reopened.allOps(group).size)
        assertEquals(1, reopened.diagnostics.logLoads)
        assertEquals(1, reopened.pendingCount(group))
    }

    @Test
    fun `given ops written behind the snapshot's back then reopen refolds instead of trusting it`() = runTest {
        repository.append(group, OpPayload.AddMember(alice, "Alice"), 1)

        // An op row the snapshot has never seen (e.g. a crash after insert but
        // before the projection transaction): the row-count gate must reject.
        val remote = GroupLedger(group, remoteDevice)
        val extra = remote.append(OpPayload.AddMember(bob, "Bob"), 2)
        db.opDao().insertAll(listOf(OpCodec.toEntity(extra)))

        val reopened = LedgerRepository(db, localDevice)
        val state = reopened.state(group)

        assertEquals(0, reopened.diagnostics.snapshotRestores)
        assertEquals(2, state.members.size)
    }

    @Test
    fun `given a repository reopened over the same database then it folds the persisted ops to the same state`() = runTest {
        repository.append(group, OpPayload.AddMember(alice, "Alice"), 1)
        repository.append(
            group,
            expensePayload(ExpenseId("e1"), 500).let {
                // expense referencing bob who is unknown -> quarantined, still persisted
                it
            },
            2,
        )
        val before = repository.state(group).canonicalBytes()

        val reopened = LedgerRepository(db, localDevice)
        assertArrayEquals(before, reopened.state(group).canonicalBytes())
    }

    @Test
    fun `given observers on the state flow then every append and ingest emits the new fold`() = runTest {
        // The flow is a conflating StateFlow: intermediate states may be skipped,
        // so assertions await each monotonic milestone rather than exact frames.
        repository.observeState(group).test {
            suspend fun awaitMemberCount(expected: Int) {
                while (awaitItem().members.size != expected) {
                    // keep consuming until the fold containing the change arrives
                }
            }

            repository.append(group, OpPayload.AddMember(alice, "Alice"), 1)
            awaitMemberCount(1)

            repository.append(group, OpPayload.AddMember(bob, "Bob"), 2)
            awaitMemberCount(2)

            val remote = GroupLedger(group, remoteDevice)
            val op = remote.append(OpPayload.AddMember(MemberId("0c000000-0000-4000-8000-000000000003"), "Carol"), 3)
            repository.ingest(group, listOf(op))
            awaitMemberCount(3)

            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `given a fold then the materialized tables mirror the state for direct queries`() = runTest {
        repository.append(group, OpPayload.AddMember(alice, "Alice"), 1)
        repository.append(group, OpPayload.AddMember(bob, "Bob"), 2)
        repository.append(group, expensePayload(ExpenseId("e1"), 1000), 3)
        repository.append(group, OpPayload.EditExpense(ExpenseId("e1"), ExpenseEdit(description = "edited")), 4)
        repository.append(group, OpPayload.DeleteExpense(ExpenseId("e1")), 5)

        val members = db.stateDao().membersForGroup(group.value)
        assertEquals(listOf("Alice", "Bob"), members.map { it.name })
        val expenses = db.stateDao().expensesForGroup(group.value)
        assertEquals(1, expenses.size)
        assertTrue(expenses.single().deleted)
    }

    @Test
    fun `given the Ledger interface append then the repository stamps a plausible wall clock itself`() = runTest {
        val ledger: app.dibs.core.domain.ledger.Ledger = repository
        val op = ledger.append(group, OpPayload.AddMember(alice, "Alice"))
        assertTrue("wall clock should be stamped, got ${op.wallClockMillis}", op.wallClockMillis > 0)
    }

    @Test
    fun `given groups created and ingested then observeGroupIds tracks them across reopen`() = runTest {
        repository.observeGroupIds().test {
            assertEquals(emptySet<GroupId>(), awaitItem())

            repository.append(group, OpPayload.AddMember(alice, "Alice"), 1)
            assertEquals(setOf(group), awaitItem())

            val other = GroupId("22222222-0000-4000-8000-000000000000")
            val remote = GroupLedger(other, remoteDevice)
            remote.append(OpPayload.AddMember(bob, "Bob"), 1)
            repository.ingest(other, remote.log.all)
            assertEquals(setOf(group, other), awaitItem())

            cancelAndConsumeRemainingEvents()
        }

        // A fresh repository over the same database sees the persisted groups.
        val reopened = LedgerRepository(db, localDevice)
        reopened.observeGroupIds().test {
            assertEquals(2, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `given ops with missing parents then they persist and are reported pending until the parents arrive`() = runTest {
        val remote = GroupLedger(group, remoteDevice)
        val parent = remote.append(OpPayload.AddMember(alice, "Alice"), 1)
        val child = remote.append(OpPayload.AddMember(bob, "Bob"), 2)

        repository.ingest(group, listOf(child))
        assertEquals(1, repository.pendingCount(group))
        assertEquals(0, repository.state(group).members.size)

        repository.ingest(group, listOf(parent))
        assertEquals(0, repository.pendingCount(group))
        assertEquals(2, repository.state(group).members.size)
    }
}
