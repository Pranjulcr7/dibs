// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.ledger.LedgerTestFixtures.addExpense
import app.dibs.core.domain.ledger.LedgerTestFixtures.addMember
import app.dibs.core.domain.ledger.LedgerTestFixtures.alice
import app.dibs.core.domain.ledger.LedgerTestFixtures.bob
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceA
import app.dibs.core.domain.ledger.LedgerTestFixtures.deviceB
import app.dibs.core.domain.ledger.LedgerTestFixtures.expenseId
import app.dibs.core.domain.ledger.LedgerTestFixtures.group
import app.dibs.core.domain.ledger.LedgerTestFixtures.settlementId
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.util.UUID
import kotlin.random.Random

/**
 * The M2 centerpiece: random interleaved op streams across simulated devices,
 * delivered in random orders with duplicates, drops, delays, and a partitioned
 * device that receives its backlog much later — after which every device must
 * fold to **byte-identical** state (CLAUDE.md invariant 6).
 */
class ConvergenceSimulationTest : FunSpec({

    fun runScenario(seed: Long): List<LedgerTestFixtures.SimDevice> {
        val random = Random(seed)
        val deviceCount = random.nextInt(2, 6)
        val devices = List(deviceCount) {
            LedgerTestFixtures.SimDevice(DeviceId(UUID(random.nextLong(), random.nextLong()).toString()))
        }
        val partitioned = devices[random.nextInt(deviceCount)]
        val members = mutableListOf<MemberId>()
        val expenses = mutableListOf<ExpenseId>()
        var nextEntity = 0

        fun randomAction(actor: LedgerTestFixtures.SimDevice) {
            val knownMembers = actor.state().members.keys.toList()
            val knownExpenses = actor.state().expenses.keys.toList()
            when (random.nextInt(10)) {
                in 0..1 -> {
                    val member = MemberId(UUID(random.nextLong(), random.nextLong()).toString())
                    members.add(member)
                    // Deliberately reuse a small name pool so same-name members
                    // (edge 26) arise naturally in random runs.
                    actor.act(addMember(member, listOf("Sam", "Alex", "Kai")[random.nextInt(3)]))
                    // Occasionally toggle activation and touch group metadata so
                    // the 3b payloads ride through every convergence scenario;
                    // concurrent CreateGroups exercise deterministic quarantine.
                    if (random.nextInt(4) == 0) actor.act(OpPayload.DeactivateMember(member))
                    if (random.nextInt(6) == 0) {
                        actor.act(
                            OpPayload.CreateGroup(
                                name = "grp-${random.nextInt(3)}",
                                defaultCurrency = app.dibs.core.domain.money.CurrencyCode("USD"),
                            ),
                        )
                    }
                    if (random.nextInt(6) == 0) {
                        actor.act(OpPayload.EditGroup(name = "renamed-${random.nextInt(10)}"))
                    }
                }
                in 2..4 -> if (knownMembers.isNotEmpty()) {
                    val id = expenseId(nextEntity++)
                    expenses.add(id)
                    val payer = knownMembers[random.nextInt(knownMembers.size)]
                    val participants = knownMembers.shuffled(random).take(random.nextInt(1, knownMembers.size + 1))
                    val total = random.nextLong(0, 100_000L)
                    val owed = app.dibs.core.domain.DomainArbs.composition(participants, total, random)
                    actor.act(
                        LedgerTestFixtures.addExpense(id, payer, total, owed.mapValues { Money(it.value) }),
                    )
                }
                in 5..6 -> if (knownExpenses.isNotEmpty()) {
                    val id = knownExpenses[random.nextInt(knownExpenses.size)]
                    actor.act(
                        OpPayload.EditExpense(
                            id,
                            when (random.nextInt(3)) {
                                0 -> ExpenseEdit(description = "edit-${random.nextInt(1000)}")
                                1 -> ExpenseEdit(note = NoteEdit("note-${random.nextInt(1000)}"))
                                else -> ExpenseEdit(dateEpochDay = random.nextLong(19_000, 21_000))
                            },
                        ),
                    )
                }
                7 -> if (knownExpenses.isNotEmpty()) {
                    val id = knownExpenses[random.nextInt(knownExpenses.size)]
                    // Delete or restore — concurrent delete+edit interleavings
                    // arise naturally across devices.
                    if (actor.state().expenses.getValue(id).deleted) {
                        actor.act(OpPayload.RestoreExpense(id))
                    } else {
                        actor.act(OpPayload.DeleteExpense(id))
                    }
                }
                8 -> if (knownMembers.size >= 2) {
                    val from = knownMembers[random.nextInt(knownMembers.size)]
                    val to = (knownMembers - from)[random.nextInt(knownMembers.size - 1)]
                    actor.act(LedgerTestFixtures.settle(settlementId(nextEntity++), from, to, random.nextLong(1, 5_000)))
                }
                else -> if (knownMembers.isNotEmpty() && random.nextBoolean()) {
                    actor.act(OpPayload.RenameMember(knownMembers[random.nextInt(knownMembers.size)], "renamed-${random.nextInt(100)}"))
                }
            }
        }

        // Rounds of local activity plus lossy, duplicating, reordering gossip.
        val rounds = random.nextInt(10, 30)
        repeat(rounds) {
            val actor = devices[random.nextInt(deviceCount)]
            randomAction(actor)

            devices.forEach { sender ->
                devices.forEach inner@{ receiver ->
                    if (sender === receiver || receiver === partitioned) return@inner
                    if (random.nextInt(100) < 40) {
                        // Random subset (drops), shuffled (reorder), sometimes sent twice (duplicates).
                        val allOps = sender.log.all.toList()
                        val subset = allOps.filter { random.nextBoolean() }.shuffled(random)
                        receiver.receive(subset)
                        if (random.nextInt(100) < 25) receiver.receive(subset.shuffled(random))
                    }
                }
            }
        }

        // Final anti-entropy: everyone receives the union of everything, in a
        // per-device shuffled order, in chunks, with whole chunks re-delivered.
        // The partitioned device gets its entire backlog only now, long after
        // the ops were created.
        val union = devices.flatMap { it.log.all }.distinctBy { it.id }
        devices.forEach { receiver ->
            val shuffled = union.shuffled(random)
            shuffled.chunked(random.nextInt(1, 8).coerceAtLeast(1)).forEach { chunk ->
                receiver.receive(chunk)
                if (random.nextBoolean()) receiver.receive(chunk)
            }
        }
        return devices
    }

    fun assertConverged(devices: List<LedgerTestFixtures.SimDevice>) {
        val reference = devices.first().state()
        val referenceBytes = reference.canonicalBytes()
        devices.forEach { device ->
            device.log.pending.shouldBeEmpty()
            device.state().canonicalBytes().contentEquals(referenceBytes) shouldBe true
            device.state().balances() shouldBe reference.balances()
        }
        // The folded state must also equal a single-shot fold of the op union
        // on a fresh device — merge order is irrelevant, only the set matters.
        val fresh = LedgerTestFixtures.SimDevice(DeviceId("ffffffff-ffff-4fff-8fff-ffffffffffff"))
        fresh.receive(devices.first().log.all)
        fresh.state().canonicalBytes().contentEquals(referenceBytes) shouldBe true
    }

    test("given 500 random interleaved op streams with drops, duplicates, reordering, and a partitioned device then every device folds to byte-identical state") {
        checkAll(500, Arb.long()) { seed ->
            assertConverged(runScenario(seed))
        }
    }

    test("given a device partitioned from the start when it receives the full backlog much later then it converges byte-identically") {
        val a = LedgerTestFixtures.SimDevice(deviceA)
        val b = LedgerTestFixtures.SimDevice(deviceB)
        a.act(addMember(alice, "Alice"))
        a.act(addMember(bob, "Bob"))
        a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), bob to Money(500))))
        a.act(OpPayload.EditExpense(expenseId(1), ExpenseEdit(description = "later")))
        a.act(OpPayload.DeleteExpense(expenseId(1)))
        a.act(OpPayload.RestoreExpense(expenseId(1)))

        // B missed everything; the backlog arrives shuffled, in dribs, twice.
        val backlog = a.log.all.toList()
        val random = Random(7)
        backlog.shuffled(random).chunked(2).forEach { chunk ->
            b.receive(chunk)
            b.receive(chunk)
        }

        b.log.pending.shouldBeEmpty()
        b.state().canonicalBytes().contentEquals(a.state().canonicalBytes()) shouldBe true
    }

    test("given a concurrent delete and edit of the same expense then both merge orders yield byte-identical converged state") {
        fun build(mergeEditFirst: Boolean): ByteArray {
            val a = LedgerTestFixtures.SimDevice(deviceA)
            val b = LedgerTestFixtures.SimDevice(deviceB)
            a.act(addMember(alice, "Alice"))
            a.act(addMember(bob, "Bob"))
            val expense = a.act(addExpense(expenseId(1), alice, 1000, mapOf(alice to Money(500), bob to Money(500))))
            b.receive(a.log.all)

            val delete = a.act(OpPayload.DeleteExpense(expenseId(1)))
            val edit = b.act(OpPayload.EditExpense(expenseId(1), ExpenseEdit(description = "concurrent edit")))

            val c = LedgerTestFixtures.SimDevice(LedgerTestFixtures.deviceC)
            c.receive(listOf(expense))
            if (mergeEditFirst) {
                c.receive(a.log.all.filter { it.id != delete.id })
                c.receive(listOf(edit))
                c.receive(listOf(delete))
            } else {
                c.receive(a.log.all)
                c.receive(b.log.all)
            }
            c.state().expenses.getValue(expenseId(1)).deleted shouldBe true
            return c.state().canonicalBytes()
        }
        build(mergeEditFirst = true).contentEquals(build(mergeEditFirst = false)) shouldBe true
    }

    test("given the same op set on every device then even quarantined and pending bookkeeping is identical") {
        // A malformed stream: expense referencing an unknown member, delivered to
        // two devices in different orders — the quarantine must match bytewise.
        val a = LedgerTestFixtures.SimDevice(deviceA)
        a.act(addMember(alice, "Alice"))
        val ghostExpense = OpFactory.create(
            group,
            deviceB,
            lamport = 5,
            parents = a.log.heads,
            wallClockMillis = 999,
            payload = addExpense(expenseId(2), bob, 700, mapOf(bob to Money(700))),
        )
        val x = LedgerTestFixtures.SimDevice(deviceA)
        val y = LedgerTestFixtures.SimDevice(deviceB)
        x.receive(a.log.all + ghostExpense)
        y.receive(listOf(ghostExpense))
        y.receive(a.log.all)

        x.state().canonicalBytes().contentEquals(y.state().canonicalBytes()) shouldBe true
        x.state().quarantined.size shouldBe 1
    }
})
