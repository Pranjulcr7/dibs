// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dibs.core.data.db.DibsDatabase
import app.dibs.core.data.key.AndroidKeystoreCipher
import app.dibs.core.data.key.WrappedPassphraseStore
import app.dibs.core.data.ledger.LedgerRepository
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.ExpenseFields
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device verification of the one path no JVM test can cover (DECISIONS.md
 * D18): the production startup sequence — SQLCipher database opened with a
 * passphrase wrapped by a real Android Keystore key — writes, closes, reopens,
 * and reads back identical state.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keyFile: File get() = context.filesDir.resolve("androidtest-db.key")

    private val group = GroupId("11111111-0000-4000-8000-000000000000")
    private val device = DeviceId("aa000000-0000-4000-8000-000000000000")
    private val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    private val bob = MemberId("0b000000-0000-4000-8000-000000000002")

    @Before
    fun cleanSlate() {
        context.deleteDatabase(DibsDatabase.NAME)
        keyFile.delete()
        context.filesDir.resolve("${DibsDatabase.NAME}.version").delete()
        context.filesDir.resolve("db-backups").deleteRecursively()
    }

    @After
    fun tearDown() = cleanSlate()

    private fun openEncrypted(): DibsDatabase =
        DibsDatabase.buildEncrypted(context, WrappedPassphraseStore(keyFile, AndroidKeystoreCipher()))

    private fun expensePayload() = OpPayload.AddExpense(
        expenseId = ExpenseId("e1000000-0000-4000-8000-000000000000"),
        expense = ExpenseFields(
            description = "on-device dinner",
            currency = CurrencyCode("USD"),
            total = Money(1000),
            paidBy = mapOf(alice to Money(1000)),
            owedBy = mapOf(alice to Money(500), bob to Money(500)),
            dateEpochDay = 20_000,
        ),
    )

    @Test
    fun keystoreWrappedPassphraseRoundTripsAcrossStoreInstances() {
        val first = WrappedPassphraseStore(keyFile, AndroidKeystoreCipher()).getOrCreatePassphrase()
        val second = WrappedPassphraseStore(keyFile, AndroidKeystoreCipher()).getOrCreatePassphrase()

        assertEquals(32, first.size)
        assertArrayEquals("a fresh store instance must unwrap the same passphrase", first, second)
        assertFalse(
            "the file on disk must hold ciphertext, not the passphrase",
            keyFile.readBytes().contentEquals(first),
        )
    }

    @Test
    fun encryptedDatabaseWritesCloseReopenAndReadBackIdenticalState() = runBlocking {
        val db = openEncrypted()
        val repository = LedgerRepository(db, device)
        repository.append(group, OpPayload.AddMember(alice, "Alice"), wallClockMillis = 1)
        repository.append(group, OpPayload.AddMember(bob, "Bob"), wallClockMillis = 2)
        repository.append(group, expensePayload(), wallClockMillis = 3)
        val stateBefore = repository.state(group).canonicalBytes()
        val opCountBefore = db.opDao().countForGroup(group.value)
        db.close()

        // Fresh database instance, fresh key store instance: the full cold-start
        // path — unwrap passphrase via Keystore, open SQLCipher file, refold.
        val reopened = openEncrypted()
        val reopenedRepository = LedgerRepository(reopened, device)

        assertEquals(opCountBefore, reopened.opDao().countForGroup(group.value))
        assertArrayEquals(stateBefore, reopenedRepository.state(group).canonicalBytes())
        assertEquals(2, reopenedRepository.state(group).members.size)
        reopened.close()
    }

    @Test
    fun databaseFileOnDiskIsNotPlaintextSqlite() = runBlocking {
        val db = openEncrypted()
        LedgerRepository(db, device).append(group, OpPayload.AddMember(alice, "Alice"), wallClockMillis = 1)
        db.close()

        // Every plaintext SQLite file starts with the magic string "SQLite format 3".
        val headerPrefix = context.getDatabasePath(DibsDatabase.NAME).readBytes().take(15).toByteArray()
        assertFalse(
            "database file must be encrypted at rest (NFR-11)",
            headerPrefix.contentEquals("SQLite format 3".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun databaseDoesNotOpenWithoutTheOriginalPassphrase() = runBlocking {
        val db = openEncrypted()
        LedgerRepository(db, device).append(group, OpPayload.AddMember(alice, "Alice"), wallClockMillis = 1)
        db.close()

        // Losing the wrapped key file means a new random passphrase: the existing
        // ciphertext file must be unreadable, proving the passphrase is load-bearing.
        keyFile.delete()
        val wrongKeyDb = openEncrypted()
        val failed = runCatching { wrongKeyDb.opDao().countForGroup(group.value) }.isFailure
        runCatching { wrongKeyDb.close() }
        assertTrue("opening with a different passphrase must fail", failed)
    }
}
