// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.members

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.dibs.feature.groups.R
import java.io.File

/**
 * Hands an invite file to the share sheet (FR-Y1). The file leaves the app
 * only through this explicit act; it is staged in the same app-private cache
 * directory as update files and overwritten by the next invite.
 */
internal object InviteSharing {

    /** Invite files carry this extension so a receiving phone can offer Dibs for them. */
    const val FILE_EXTENSION = "dibsinvite"

    private const val FILE_NAME = "dibs-invite.$FILE_EXTENSION"
    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val STAGING_DIR = "sync"

    fun stage(context: Context, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
        val file = File(dir, FILE_NAME).apply { writeBytes(bytes) }
        return FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }

    fun shareIntent(context: Context, uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            // A caption for chat apps; it deliberately contains no link and no key.
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.invite_share_text))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.invite_share_chooser))
    }
}
