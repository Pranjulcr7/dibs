// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.settings.UserSettings
import app.dibs.core.ui.theme.DibsTheme
import app.dibs.feature.groups.detail.BalancesTabContent
import app.dibs.feature.groups.detail.GroupDetailViewModel
import app.dibs.feature.groups.detail.SettleTabContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w480dp-h1600dp")
class SettleTest {

    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val groupId = GroupId("11111111-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")
    private val usd = CurrencyCode("USD")
    private lateinit var ledger: FakeLedger
    private lateinit var settings: FakeSettingsStore

    @Before
    fun setUp() {
        ledger = FakeLedger()
        settings = FakeSettingsStore(UserSettings(selfMembers = mapOf(groupId to alice)))
    }

    /** Main is swapped only for ViewModel tests; Compose dialogs need the real main dispatcher. */
    private fun runViewModelTest(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) {
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) { block() }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun detailViewModel(): GroupDetailViewModel {
        val syncMeta = FakeSyncMetaStore()
        return GroupDetailViewModel(
            SavedStateHandle(mapOf("groupId" to groupId.value)),
            ledger,
            settings,
            FakeBundleSync(ledger, syncMeta),
            syncMeta,
            FakeIdentitySource(),
        )
    }

    private suspend fun seedDebt() {
        ledger.append(groupId, OpPayload.CreateGroup("Trip", defaultCurrency = usd))
        ledger.append(groupId, OpPayload.AddMember(alice, "Alice"))
        ledger.append(groupId, OpPayload.AddMember(bob, "Bob"))
        ledger.append(
            groupId,
            OpPayload.AddExpense(
                app.dibs.core.domain.model.ExpenseId("e1000000-0000-4000-8000-000000000000"),
                ExpenseFields(
                    description = "dinner",
                    currency = usd,
                    total = Money(1000),
                    paidBy = mapOf(alice to Money(1000)),
                    owedBy = mapOf(alice to Money(500), bob to Money(500)),
                    dateEpochDay = 20_000,
                ),
            ),
        )
    }

    @Test
    fun `given a recorded partial settlement then balances shrink and history lists the payment`() = runViewModelTest {
        seedDebt()
        val viewModel = detailViewModel()
        viewModel.recordSettlement(bob, alice, Money(200), usd)
        dispatcher.scheduler.advanceUntilIdle()

        val state = ledger.state(groupId)
        assertEquals(1, state.settlements.size)
        assertEquals(Money(300), state.balances().getValue(usd).first { it.memberId == alice }.net)
    }

    @Test
    fun `given a write-off then the pair disappears and the history labels it as written off`() = runViewModelTest {
        seedDebt()
        val viewModel = detailViewModel()
        viewModel.recordSettlement(bob, alice, Money(500), usd, kind = "writeoff")
        dispatcher.scheduler.advanceUntilIdle()

        val state = ledger.state(groupId)
        assertEquals("writeoff", state.settlements.values.single().kind)
        assertTrue(state.pairwiseDebts()[usd].orEmpty().isEmpty())
    }

    @Test
    fun `given the settle tab then the suggested transfer is listed with a record button`() {
        val state = kotlinx.coroutines.runBlocking {
            seedDebt()
            ledger.state(groupId)
        }
        compose.setContent {
            DibsTheme {
                SettleTabContent(state, alice) { _, _, _, _ -> }
            }
        }
        compose.onNodeWithText("Bob owes Alice · \$5.00").assertIsDisplayed()
        compose.onNodeWithTag("record-${bob.value}").assertIsDisplayed()
    }

    // NOTE: AlertDialog windows never reach idle under Robolectric's compose
    // idling strategy (AppNotIdleException on render), so dialog interaction is
    // exercised on-device; the recording path itself is covered by the
    // ViewModel tests above and the dialog is presentation glue.

    @Test
    fun `given the balances tab then pairwise debts show and the simplify toggle swaps the view with its explanation`() {
        val state = kotlinx.coroutines.runBlocking {
            seedDebt()
            ledger.state(groupId)
        }
        compose.setContent {
            DibsTheme {
                BalancesTabContent(state, alice) { _, _, _, _ -> }
            }
        }
        compose.onNodeWithText("Bob owes Alice").assertIsDisplayed()
        compose.onNodeWithTag("simplify-toggle").performClick()
        compose.onNodeWithText(
            "Fewer payments that settle the same totals — you may pay someone you never split with.",
        ).assertIsDisplayed()
    }

    @Test
    fun `given a single-member group then the settle tab explains there is nobody to settle with`() {
        val state = kotlinx.coroutines.runBlocking {
            ledger.append(groupId, OpPayload.CreateGroup("Solo", defaultCurrency = usd))
            ledger.append(groupId, OpPayload.AddMember(alice, "Alice"))
            ledger.state(groupId)
        }
        compose.setContent {
            DibsTheme { SettleTabContent(state, alice) { _, _, _, _ -> } }
        }
        compose.onNodeWithText("It's just you here").assertIsDisplayed()
    }
}
