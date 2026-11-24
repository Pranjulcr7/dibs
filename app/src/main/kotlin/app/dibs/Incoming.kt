// SPDX-License-Identifier: Apache-2.0
package app.dibs

import android.content.Intent
import android.net.Uri

/** Something handed to Dibs from outside: an invite link, or a file to open (FR-Y1). */
sealed interface Incoming {

    /** A `dibs://join` link — tapped, or shared to Dibs as text. */
    data class Link(val text: String) : Incoming

    /** A content or file URI: an invite file or an update file. Read later, bounded, by the join screen. */
    data class File(val uri: String) : Incoming

    /** The navigation route that handles this; values are URL-encoded because links contain `&` and `=`. */
    fun route(): String = when (this) {
        is Link -> "join?link=${encode(text)}"
        is File -> "join?file=${encode(uri)}"
    }

    /** Percent-encoding the navigation library decodes back exactly (`+` would not round-trip a space). */
    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

/**
 * Decides what, if anything, an incoming intent asks Dibs to open.
 *
 * Everything here is attacker-controlled (edge 76): any app can fire these
 * intents. So this only *classifies* — it never reads a file, never adopts a
 * key, never navigates anywhere but the join screen, which itself writes
 * nothing without an explicit choice by the user.
 */
object IncomingIntents {

    private const val LINK_PREFIX = "dibs://join?"
    private const val MAX_TEXT = 4_096
    private val fileSchemes = setOf("content", "file")

    fun from(intent: Intent?): Incoming? {
        if (intent == null) return null
        @Suppress("DEPRECATION") // the typed getParcelableExtra needs API 33; minSdk is 26
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        return classify(
            action = intent.action,
            dataScheme = intent.data?.scheme,
            data = intent.dataString,
            stream = stream?.toString(),
            streamScheme = stream?.scheme,
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
        )
    }

    /** The decision itself, free of Android types so every branch is unit-tested. */
    @Suppress("LongParameterList") // the six intent fields that matter, named
    internal fun classify(
        action: String?,
        dataScheme: String?,
        data: String?,
        stream: String?,
        streamScheme: String?,
        text: String?,
    ): Incoming? = when (action) {
        Intent.ACTION_VIEW -> when {
            data == null -> null
            dataScheme == "dibs" -> Incoming.Link(data)
            dataScheme in fileSchemes -> Incoming.File(data)
            else -> null
        }
        Intent.ACTION_SEND -> when {
            stream != null && streamScheme in fileSchemes -> Incoming.File(stream)
            else -> linkIn(text)?.let(Incoming::Link)
        }
        else -> null
    }

    /** Pulls a join link out of shared text ("Join my group: dibs://join?…"); null when there is none. */
    internal fun linkIn(text: String?): String? {
        if (text == null || text.length > MAX_TEXT) return null
        val start = text.indexOf(LINK_PREFIX)
        if (start < 0) return null
        val end = text.indexOfFirst(start) { it.isWhitespace() }
        return text.substring(start, if (end < 0) text.length else end)
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return -1
    }
}
