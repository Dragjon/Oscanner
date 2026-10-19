package dev.olaf.oscanner.feature.document

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private const val MIN_LENGTH = 6

/** Asks for the password a shared PDF will need. One field, with a show/hide toggle to check typing. */
@Composable
internal fun PasswordDialog(onShare: (String) -> Unit, onDismiss: () -> Unit) {
    // remember, not rememberSaveable: a password shouldn't be written into saved instance state.
    var password by remember { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val long = password.length >= MIN_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(stringResource(R.string.document_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.document_password_body), color = colors.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.document_password_hint)) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    supportingText = { if (password.isNotEmpty() && !long) Text(stringResource(R.string.document_password_short)) },
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(if (visible) R.string.document_password_hide else R.string.document_password_show),
                            )
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.onSurfaceVariant,
                        unfocusedBorderColor = colors.outlineVariant,
                        cursorColor = colors.primary,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onShare(password) }, enabled = long) { Text(stringResource(R.string.document_password_share)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.document_cancel), color = colors.onSurfaceVariant) }
        },
    )
}
