// SPDX-License-Identifier: Apache-2.0
package app.dibs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F22/F23 entry points, and edge 76: any app can send these intents. */
class IncomingIntentsTest {

    private val link = "dibs://join?g=abc&k=AAAA&i=def&sig=BBBB"
    private val view = "android.intent.action.VIEW"
    private val send = "android.intent.action.SEND"

    @Suppress("LongParameterList") // mirrors IncomingIntents.classify, with defaults for readability
    private fun classify(
        action: String?,
        dataScheme: String? = null,
        data: String? = null,
        stream: String? = null,
        streamScheme: String? = null,
        text: String? = null,
    ) = IncomingIntents.classify(action, dataScheme, data, stream, streamScheme, text)

    @Test
    fun `given a tapped invite link then it is a link`() {
        assertEquals(Incoming.Link(link), classify(view, dataScheme = "dibs", data = link))
    }

    @Test
    fun `given a file opened with Dibs then it is a file, for content and file URIs alike`() {
        val content = "content://com.whatsapp.provider.media/item/42"
        assertEquals(Incoming.File(content), classify(view, dataScheme = "content", data = content))
        assertEquals(
            Incoming.File("file:///sdcard/Download/x.dibsinvite"),
            classify(view, dataScheme = "file", data = "file:///sdcard/Download/x.dibsinvite"),
        )
    }

    @Test
    fun `given a file shared to Dibs then it is a file`() {
        val uri = "content://media/external/file/7"
        assertEquals(Incoming.File(uri), classify(send, stream = uri, streamScheme = "content"))
    }

    @Test
    fun `given text shared to Dibs that contains an invite link then the link is extracted`() {
        assertEquals(Incoming.Link(link), classify(send, text = "Join my group: $link\nSee you there"))
        assertEquals(Incoming.Link(link), classify(send, text = link))
    }

    @Test
    fun `edge 76 - given other schemes, other actions, or nothing useful then nothing is opened`() {
        assertNull(classify(view, dataScheme = "https", data = "https://example.com/?next=dibs://join?g=1"))
        assertNull(classify(view, dataScheme = "javascript", data = "javascript:alert(1)"))
        assertNull(classify(view))
        assertNull(classify(send, stream = "https://evil.example/x", streamScheme = "https"))
        assertNull(classify(send, text = "no link here"))
        assertNull(classify(send, text = "x".repeat(5_000) + link))
        assertNull(classify("android.intent.action.MAIN", dataScheme = "dibs", data = link))
        assertNull(classify(null, dataScheme = "dibs", data = link))
    }

    @Test
    fun `given an incoming item then its route URL-encodes the value so link parameters survive navigation`() {
        val route = Incoming.Link(link).route()

        assertEquals("join?link=dibs%3A%2F%2Fjoin%3Fg%3Dabc%26k%3DAAAA%26i%3Ddef%26sig%3DBBBB", route)
        assertEquals(false, route.substringAfter("link=").contains("&"))
    }
}
