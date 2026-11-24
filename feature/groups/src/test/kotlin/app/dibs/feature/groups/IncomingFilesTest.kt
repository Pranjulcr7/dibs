// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups

import app.dibs.feature.groups.join.IncomingFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Edge 29 (D45): files from outside are read with a hard cap, never unbounded. */
class IncomingFilesTest {

    /** A stream of [length] bytes that never materialises them, so the cap can be tested cheaply. */
    private class Endless(private var remaining: Long) : InputStream() {
        var bytesServed = 0L
        override fun read(): Int = if (remaining-- > 0) 0.also { bytesServed++ } else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(length.toLong(), remaining).toInt()
            remaining -= n
            bytesServed += n
            return n
        }
    }

    @Test
    fun `given a file under the cap then every byte is returned`() {
        val data = ByteArray(200_000) { (it % 251).toByte() }

        val read = IncomingFiles.readBounded(ByteArrayInputStream(data)) as IncomingFiles.Read.Bytes

        assertTrue(read.bytes.contentEquals(data))
    }

    @Test
    fun `given a file exactly at the cap then it is accepted`() {
        val read = IncomingFiles.readBounded(Endless(IncomingFiles.MAX_BYTES.toLong()))

        assertEquals(IncomingFiles.MAX_BYTES, (read as IncomingFiles.Read.Bytes).bytes.size)
    }

    @Test
    fun `edge 29 - given a file over the cap then it is refused without reading the rest`() {
        val huge = Endless(10L * 1024 * 1024 * 1024) // 10 GB, if anything tried to read it all

        val read = IncomingFiles.readBounded(huge)

        assertEquals(IncomingFiles.Read.TooLarge, read)
        assertTrue("stopped just past the cap", huge.bytesServed < IncomingFiles.MAX_BYTES + 1_000_000L)
    }

    @Test
    fun `given an empty file then it is an empty read, not an error`() {
        val read = IncomingFiles.readBounded(ByteArrayInputStream(ByteArray(0))) as IncomingFiles.Read.Bytes

        assertEquals(0, read.bytes.size)
    }
}
