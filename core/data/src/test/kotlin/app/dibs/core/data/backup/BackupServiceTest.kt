// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.backup

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dibs.core.data.db.DibsDatabase
import app.dibs.core.data.key.PassphraseCipher
import app.dibs.core.data.key.WrappedPassphraseStore
import app.dibs.core.data.ledger.LedgerRepository
import app.dibs.core.data.settings.DataStoreSettingsStore
import app.dibs.core.data.settings.UserSettingsSerializer
import app.dibs.core.domain.ledger.BackupService
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import app.dibs.core.domain.settings.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")

    private val xorCipher = object : PassphraseCipher {
        override fun wrap(plaintext: ByteArray) = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun unwrap(wrapped: ByteArray) = wrapped.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private val scopes = mutableListOf<CoroutineScope>()
    private val databases = mutableListOf<DibsDatabase>()

    private fun environment(tag: String): Triple<LedgerRepository, DataStoreSettingsStore, DefaultBackupService> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, DibsDatabase::class.java)
            .allowMainThreadQueries().build().also(databases::add)
        val repo = LedgerRepository(db, DeviceId("de000000-0000-4000-8000-00000000000$tag"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also(scopes::add)
        val store = DataStoreSettingsStore(
            DataStoreFactory.create(
                serializer = UserSettingsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { UserSettings() },
                scope = scope,
            ) { File(tmp.root, "settings-$tag.json") },
        )
        val masterKey = WrappedPassphraseStore(File(tmp.root, "master-$tag.key"), xorCipher)
        return Triple(repo, store, DefaultBackupService(repo, store, masterKey))
    }

    @Before
    fun setUp() = Unit

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        databases.forEach { it.close() }
    }

    @Test
    fun `given a backup restored onto a fresh device then the state is byte-identical and the identity is adopted`() = runTest {
        val (sourceRepo, sourceStore, sourceService) = environment("a")
        sourceStore.update { it.copy(displayName = "Priya", onboardingComplete = true) }
        sourceRepo.append(group, OpPayload.CreateGroup("Trip", defaultCurrency = CurrencyCode("USD")), 1)
        sourceRepo.append(group, OpPayload.AddMember(alice, "Alice"), 2)
        sourceRepo.append(group, OpPayload.AddMember(bob, "Bob"), 3)
        sourceRepo.append(
            group,
            OpPayload.AddExpense(
                ExpenseId("e1"),
                ExpenseFields(
                    description = "dinner",
                    currency = CurrencyCode("USD"),
                    total = Money(1000),
                    paidBy = mapOf(alice to Money(1000)),
                    owedBy = mapOf(alice to Money(500), bob to Money(500)),
                    dateEpochDay = 20_000,
                ),
            ),
            4,
        )
        val bytes = sourceService.createBackup("passphrase".toCharArray())

        val (targetRepo, targetStore, targetService) = environment("b")
        val result = targetService.restoreBackup(bytes, "passphrase".toCharArray())
        val restored = result as BackupService.RestoreResult.Restored
        assertEquals(1, restored.groups)
        assertEquals(4, restored.opsAdded)
        assertTrue(restored.identityAdopted)

        assertArrayEquals(
            sourceRepo.state(group).canonicalBytes(),
            targetRepo.state(group).canonicalBytes(),
        )
        assertEquals("Priya", targetStore.settings.first().displayName)
        assertTrue(targetStore.settings.first().onboardingComplete)
    }

    @Test
    fun `given the same backup restored twice then the second restore adds nothing — merge is idempotent`() = runTest {
        val (sourceRepo, _, sourceService) = environment("a")
        sourceRepo.append(group, OpPayload.AddMember(alice, "Alice"), 1)
        val bytes = sourceService.createBackup("pw".toCharArray())

        val (targetRepo, _, targetService) = environment("b")
        targetService.restoreBackup(bytes, "pw".toCharArray())
        val second = targetService.restoreBackup(bytes, "pw".toCharArray())
        assertEquals(0, (second as BackupService.RestoreResult.Restored).opsAdded)
        assertEquals(1, targetRepo.state(group).members.size)
    }

    @Test
    fun `given a device with existing data then restore merges instead of replacing`() = runTest {
        val (sourceRepo, _, sourceService) = environment("a")
        sourceRepo.append(group, OpPayload.AddMember(alice, "Alice"), 1)
        val bytes = sourceService.createBackup("pw".toCharArray())

        val (targetRepo, _, targetService) = environment("b")
        targetRepo.append(group, OpPayload.AddMember(bob, "Bob"), 5)
        targetService.restoreBackup(bytes, "pw".toCharArray())

        assertEquals(2, targetRepo.state(group).members.size)
    }

    @Test
    fun `given the wrong passphrase then restore reports it and writes nothing`() = runTest {
        val (sourceRepo, _, sourceService) = environment("a")
        sourceRepo.append(group, OpPayload.AddMember(alice, "Alice"), 1)
        val bytes = sourceService.createBackup("right".toCharArray())

        val (targetRepo, _, targetService) = environment("b")
        val result = targetService.restoreBackup(bytes, "wrong".toCharArray())
        assertEquals(BackupService.RestoreResult.WrongPassphraseOrCorrupt, result)
        assertEquals(0, targetRepo.state(group).members.size)
    }
}
