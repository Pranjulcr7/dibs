// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.crypto.IdentitySource
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.settings.UserSettings
import app.dibs.feature.groups.importer.ImportStep
import app.dibs.feature.groups.importer.ImportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

class ImportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val testKeyPair = Identity.keyPairFromMaster(ByteArray(32) { 42 })
    private val identity = object : IdentitySource {
        override suspend fun keyPair() = testKeyPair
    }
    private lateinit var ledger: FakeLedger
    private lateinit var settings: FakeSettingsStore

    private val csv = """
        Date,Description,Category,Cost,Currency,Alice,Bob
        2026-05-01,Dinner,Food,30.00,USD,15.00,-15.00
        2026-05-03,Bob paid Alice,Payment,10.00,USD,10.00,-10.00
    """.trimIndent()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        ledger = FakeLedger()
        settings = FakeSettingsStore(UserSettings(displayName = "Priya"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ImportViewModel(ledger, settings, identity)

    @Test
    fun `given a parsed file when mapped and confirmed then the new group folds to the imported ledger`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onFileLoaded(csv)
        assertTrue(vm.step.value is ImportStep.Mapping)

        vm.renameMember("Bob", "Robert")
        vm.chooseSelf("Alice")
        vm.toPreview("Lisbon crew")
        dispatcher.scheduler.advanceUntilIdle()
        val preview = vm.step.value as ImportStep.Preview
        assertFalse(preview.alreadyImported)
        assertEquals("Lisbon crew", preview.groupName)

        vm.confirm()
        dispatcher.scheduler.advanceUntilIdle()

        val groupId = ledger.observeGroupIds().first().single()
        val state = ledger.state(groupId)
        assertEquals("Lisbon crew", state.meta?.name)
        assertEquals(setOf("Alice", "Robert"), state.members.values.map { it.name }.toSet())
        assertEquals(1, state.activeExpenses.size)
        assertEquals(Money(3000), state.activeExpenses.single().fields.total)
        assertEquals(1, state.settlements.size)
        // The self choice claims the member so balances know who "you" are.
        assertEquals(
            settings.state.value.selfMembers[groupId],
            state.members.values.single { it.name == "Alice" }.id,
        )
    }

    @Test
    fun `given a file whose rows already exist then preview flags it and confirm refuses to duplicate`() = runTest(dispatcher) {
        val first = viewModel()
        first.onFileLoaded(csv)
        first.toPreview("Once")
        dispatcher.scheduler.advanceUntilIdle()
        first.confirm()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, ledger.observeGroupIds().first().size)

        val second = viewModel()
        second.onFileLoaded(csv)
        second.toPreview("Twice")
        dispatcher.scheduler.advanceUntilIdle()
        val preview = second.step.value as ImportStep.Preview
        assertTrue(preview.alreadyImported)

        second.confirm()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, ledger.observeGroupIds().first().size)
    }

    @Test
    fun `given confirm tapped twice before the write finishes then only one group is created`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onFileLoaded(csv)
        vm.toPreview("Once only")
        dispatcher.scheduler.advanceUntilIdle()

        vm.confirm()
        // A big import takes a while and the button stays on screen — the second
        // tap must be a no-op, not a second full import into a second group.
        assertTrue(vm.step.value is ImportStep.Importing)
        vm.confirm()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, ledger.observeGroupIds().first().size)
    }

    @Test
    fun `given a non-English export header then the flow reports it instead of guessing`() {
        val vm = viewModel()
        vm.onFileLoaded("Datum,Beschreibung,Kategorie,Kosten,Währung,Alice,Bob\n")
        assertTrue(vm.step.value is ImportStep.BadHeader)
    }

    @Test
    fun `given back from preview then edits to the mapping survive`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onFileLoaded(csv)
        vm.renameMember("Alice", "Alicia")
        vm.toPreview("Trip")
        dispatcher.scheduler.advanceUntilIdle()
        vm.backToMapping()
        val mapping = vm.step.value as ImportStep.Mapping
        assertEquals("Alicia", mapping.names["Alice"])
    }
}
