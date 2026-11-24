// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.feature.groups.detail.BalanceSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** FR-S6: a plain-text summary for the share sheet, never a message Dibs sends itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class BalanceSummaryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")
    private val cara = MemberId("0c000000-0000-4000-8000-000000000003")
    private val usd = CurrencyCode("USD")
    private val jpy = CurrencyCode("JPY")
    private val ledger = FakeLedger()

    private suspend fun seed() {
        ledger.append(group, OpPayload.CreateGroup("Goa trip", defaultCurrency = usd))
        ledger.append(group, OpPayload.AddMember(alice, "Alice"))
        ledger.append(group, OpPayload.AddMember(bob, "Bob"))
        ledger.append(group, OpPayload.AddMember(cara, "Cara"))
    }

    private suspend fun expense(id: String, currency: CurrencyCode, total: Long, owed: Map<MemberId, Long>) {
        ledger.append(
            group,
            OpPayload.AddExpense(
                ExpenseId(id),
                ExpenseFields(
                    description = id,
                    currency = currency,
                    total = Money(total),
                    paidBy = mapOf(alice to Money(total)),
                    owedBy = owed.mapValues { Money(it.value) },
                    dateEpochDay = 20_000,
                ),
            ),
        )
    }

    @Test
    fun `given debts in two currencies then the summary lists each debt with its own currency and exact amount`() = runTest {
        seed()
        expense("dinner", usd, 3001, mapOf(alice to 1001, bob to 1000, cara to 1000))
        expense("ramen", jpy, 3000, mapOf(alice to 1000, bob to 2000))

        val text = BalanceSummary.text(context, ledger.state(group))

        val lines = text.lines()
        assertEquals("Goa trip — who owes whom", lines.first())
        assertEquals(setOf("Bob owes Alice ¥2,000", "Bob owes Alice $10.00", "Cara owes Alice $10.00"), lines.drop(1).toSet())
        // Zero-decimal yen never grows cents (edge 6); dollars always show them.
        assertTrue(lines.none { it.contains("¥2,000.") })
    }

    @Test
    fun `given everything paid back then the summary says the group is settled`() = runTest {
        seed()
        expense("dinner", usd, 2000, mapOf(alice to 1000, bob to 1000))
        ledger.append(
            group,
            OpPayload.RecordSettlement(SettlementId("5a000000-0000-4000-8000-000000000001"), bob, alice, Money(1000), usd),
        )

        assertEquals("Goa trip: all settled up.", BalanceSummary.text(context, ledger.state(group)))
    }

    @Test
    fun `given the share action then it is a plain-text send carrying the summary and nothing else`() = runTest {
        seed()
        expense("dinner", usd, 2000, mapOf(alice to 1000, bob to 1000))
        val state = ledger.state(group)

        val chooser = BalanceSummary.shareIntent(context, state)

        @Suppress("DEPRECATION")
        val send = checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(BalanceSummary.text(context, state), send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(null, send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    }
}
