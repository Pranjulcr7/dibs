// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.dibs.core.domain.backup.PassphraseStrength
import app.dibs.core.ui.theme.Dimens

/**
 * Passphrase entry for creating a backup, with the strength meter edge 77
 * requires: WEAK passphrases can't proceed, because the resulting file may end
 * up on a shared drive and the passphrase is its only protection.
 */
@Composable
fun BackupPassphraseDialog(onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    val level = PassphraseStrength.assess(passphrase.toCharArray())
    val mismatch = repeat.isNotEmpty() && repeat != passphrase
    val canCreate = level != PassphraseStrength.Level.WEAK && repeat == passphrase && passphrase.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.backup_dialog_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Dimens.spacingMd))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup-passphrase"),
                )
                if (passphrase.isNotEmpty()) {
                    val (labelRes, color) = when (level) {
                        PassphraseStrength.Level.WEAK ->
                            R.string.backup_strength_weak to MaterialTheme.colorScheme.error
                        PassphraseStrength.Level.FAIR ->
                            R.string.backup_strength_fair to MaterialTheme.colorScheme.tertiary
                        PassphraseStrength.Level.STRONG ->
                            R.string.backup_strength_strong to MaterialTheme.colorScheme.primary
                    }
                    Text(stringResource(labelRes), style = MaterialTheme.typography.bodySmall, color = color)
                }
                Spacer(Modifier.height(Dimens.spacingSm))
                OutlinedTextField(
                    value = repeat,
                    onValueChange = { repeat = it },
                    label = { Text(stringResource(R.string.backup_passphrase_repeat)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = mismatch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup-passphrase-repeat"),
                )
                if (mismatch) {
                    Text(
                        stringResource(R.string.backup_passphrase_mismatch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase.toCharArray()) },
                enabled = canCreate,
                modifier = Modifier.testTag("backup-create"),
            ) {
                Text(stringResource(R.string.backup_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_cancel))
            }
        },
    )
}

/** Passphrase entry for restoring; no meter — the passphrase already exists. */
@Composable
fun RestorePassphraseDialog(onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.restore_dialog_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Dimens.spacingMd))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("restore-passphrase"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase.toCharArray()) },
                enabled = passphrase.isNotEmpty(),
                modifier = Modifier.testTag("restore-confirm"),
            ) {
                Text(stringResource(R.string.restore_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_cancel))
            }
        },
    )
}
