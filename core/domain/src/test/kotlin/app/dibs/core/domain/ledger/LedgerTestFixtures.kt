// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money

/** Shared fixed ids and payload builders for ledger tests. */
object LedgerTestFixtures {
    val group = GroupId("11111111-0000-4000-8000-000000000000")
    val deviceA = DeviceId("aa000000-0000-4000-8000-000000000000")
    val deviceB = DeviceId("bb000000-0000-4000-8000-000000000000")
    val deviceC = DeviceId("cc000000-0000-4000-8000-000000000000")
    val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    val bob = MemberId("0b000000-0000-4000-8000-000000000002")
    val carol = MemberId("0c000000-0000-4000-8000-000000000003")
    val usd = CurrencyCode("USD")

    fun expenseId(n: Int) = ExpenseId("e%08d-0000-4000-8000-000000000000".format(n))
    fun settlementId(n: Int) = SettlementId("5%08d-0000-4000-8000-000000000000".format(n))

    fun addMember(member: MemberId, name: String) = OpPayload.AddMember(member, name)

    fun addExpense(
        id: ExpenseId,
        payer: MemberId,
        totalMinor: Long,
        owedBy: Map<MemberId, Money>,
        description: String = "expense",
    ) = OpPayload.AddExpense(
        expenseId = id,
        expense = ExpenseFields(
            description = description,
            currency = usd,
            total = Money(totalMinor),
            paidBy = mapOf(payer to Money(totalMinor)),
            owedBy = owedBy,
            dateEpochDay = 20_000,
        ),
    )

    fun settle(id: SettlementId, from: MemberId, to: MemberId, amountMinor: Long) =
        OpPayload.RecordSettlement(id, from, to, Money(amountMinor), usd)

    /** A device with its own ledger; the unit the convergence simulation drives. */
    class SimDevice(deviceId: DeviceId, groupId: GroupId = group) {
        private val ledger = GroupLedger(groupId, deviceId)
        private var wallClock = 1_000_000L

        val log: OpLog get() = ledger.log

        fun act(payload: OpPayload, wallClockMillis: Long? = null): Op {
            wallClock += 60_000
            return ledger.append(payload, wallClockMillis ?: wallClock)
        }

        fun receive(ops: Collection<Op>) = ledger.receive(ops)

        fun state(): GroupState = ledger.state()
    }
}
