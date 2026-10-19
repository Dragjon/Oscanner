package dev.olaf.oscanner.feature.capture

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.design.SheetAction

/**
 * What a QR code or barcode says, and what can be done with it. Nothing happens on its own:
 * links open only when tapped, so a hostile code can't do anything by being seen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeSheet(code: ScannedCode, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val copied = stringResource(R.string.capture_code_copied)
    val noApp = stringResource(R.string.capture_code_no_app)
    val wifi = code.wifi

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(
                    when (code.kind) {
                        CodeKind.Link -> R.string.capture_code_link
                        CodeKind.Wifi -> R.string.capture_code_wifi
                        CodeKind.Product -> R.string.capture_code_product
                        CodeKind.Text -> R.string.capture_code_text
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            SelectionContainer {
                Text(
                    if (wifi != null) stringResource(R.string.capture_code_network, wifi.first) else code.text,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (wifi != null && wifi.second == null) {
                Text(stringResource(R.string.capture_code_no_password), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            if (code.kind == CodeKind.Link) {
                SheetAction(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.capture_code_open), Uri.parse(code.text).host, {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.text)))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
                    }
                })
            }
            wifi?.second?.let { password ->
                SheetAction(Icons.Rounded.Key, stringResource(R.string.capture_code_copy_password), null, {
                    context.copy(password)
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                })
            }
            SheetAction(Icons.Rounded.ContentCopy, stringResource(R.string.capture_code_copy), null, {
                context.copy(code.text)
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            })
            SheetAction(Icons.Rounded.Share, stringResource(R.string.capture_code_share), null, {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code.text)
                context.startActivity(Intent.createChooser(send, null))
            })
        }
    }
}

internal fun CaptureMode.label(): Int = when (this) {
    CaptureMode.Document -> R.string.capture_mode_document
    CaptureMode.IdCard -> R.string.capture_mode_id_card
    CaptureMode.Book -> R.string.capture_mode_book
    CaptureMode.Whiteboard -> R.string.capture_mode_whiteboard
    CaptureMode.Code -> R.string.capture_mode_code
}

private fun Context.copy(text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Oscanner", text))
}
