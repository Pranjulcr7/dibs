// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dibs.core.data.db.DibsDatabase
import app.dibs.core.domain.model.GroupId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultGroupKeyStoreTest {

    private val group = GroupId("11111111-0000-4000-8000-000000000000")

    private lateinit var db: DibsDatabase
    private lateinit var store: DefaultGroupKeyStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DibsDatabase::class.java)
            .allowMainThreadQueries().build()
        store = DefaultGroupKeyStore(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `given no key then keyFor is null and getOrCreate mints a stable 256-bit key`() = runTest {
        assertNull(store.keyFor(group))
        val first = store.getOrCreate(group)
        assertEquals(32, first.size)
        assertArrayEquals(first, store.getOrCreate(group))
        assertArrayEquals(first, store.keyFor(group))
    }

    @Test
    fun `given a key from a join link then adopt stores it and re-adopting the same key is a no-op`() = runTest {
        val key = ByteArray(32) { it.toByte() }
        store.adopt(group, key)
        store.adopt(group, key)
        assertArrayEquals(key, store.keyFor(group))
    }

    @Test
    fun `given a group that already holds a key then adopting a different one fails loudly`() = runTest {
        store.adopt(group, ByteArray(32) { 1 })
        val ex = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { store.adopt(group, ByteArray(32) { 2 }) }
        }
        assertTrue(checkNotNull(ex.message).contains("different key"))
    }

    @Test
    fun `given several groups then all returns every held key for the backup`() = runTest {
        val other = GroupId("22222222-0000-4000-8000-000000000000")
        val a = store.getOrCreate(group)
        val b = store.getOrCreate(other)
        val all = store.all()
        assertEquals(2, all.size)
        assertArrayEquals(a, all[group])
        assertArrayEquals(b, all[other])
    }
}
