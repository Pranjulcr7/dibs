// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.dibs.core.domain.sync.BundleSync
import app.dibs.feature.groups.R
import java.io.File

/**
 * The Android plumbing of manual sync (FR-Y1): a sealed bundle leaves the app
 * only as a file the user explicitly hands to the share sheet, and comes back
 * only as a file the user explicitly picks. Kept out of the composables so the
 * screens stay data-in, lambdas-out.
 */
internal object SyncSharing {

    /** Update files carry this extension so a receiving phone can offer Dibs for them. */
    const val FILE_EXTENSION = "dibssync"

    /** Matches the `<provider>` authority suffix in the app manifest. */
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /** Matches `res/xml/file_paths.xml`: the only directory the provider exposes. */
    private const val STAGING_DIR = "sync"

    /**
     * Writes [bytes] to the app-private staging directory and returns a
     * grantable content URI. One file per group, overwritten on every share:
     * the bundle is already encrypted, and nothing accumulates in the cache.
     */
    fun stage(context: Context, bytes: ByteArray, groupName: String?): Uri {
        val dir = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
        val file = File(dir, fileName(groupName)).apply { writeBytes(bytes) }
        return FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }

    fun shareIntent(context: Context, uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.sync_share_action))
    }

    /** A recognisable, filesystem-safe name: "Trip to Goa" → `dibs-trip-to-goa.dibssync`. */
    internal fun fileName(groupName: String?): String {
        val slug = groupName.orEmpty()
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(MAX_SLUG_LENGTH)
        return (if (slug.isEmpty()) "dibs-updates" else "dibs-$slug") + "." + FILE_EXTENSION
    }

    private const val MAX_SLUG_LENGTH = 40
}

/** Every import outcome has a specific, user-readable message (FR-Y4: never a silent failure). */
internal fun BundleSync.ImportOutcome.message(context: Context): String = when (this) {
    is BundleSync.ImportOutcome.Imported ->
        if (behind.isEmpty()) {
            context.getString(R.string.sync_imported, opsAdded)
        } else {
            context.getString(R.string.sync_imported_behind, opsAdded)
        }
    is BundleSync.ImportOutcome.NoKeyForGroup -> context.getString(R.string.sync_no_key)
    BundleSync.ImportOutcome.NotABundle -> context.getString(R.string.sync_not_a_bundle)
    is BundleSync.ImportOutcome.NeedsNewerApp -> context.getString(R.string.sync_needs_newer_app)
    BundleSync.ImportOutcome.RejectedTamperedOrWrongKey -> context.getString(R.string.sync_rejected_tampered)
    BundleSync.ImportOutcome.RejectedBadSignature -> context.getString(R.string.sync_rejected_signature)
    is BundleSync.ImportOutcome.RejectedUnknownSender -> context.getString(R.string.sync_rejected_unknown_sender)
}
