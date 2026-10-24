package dev.olaf.oscanner.feature.document

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.olaf.oscanner.core.data.LocalShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Sharing stops by itself after this long, in case the dialog is left open. */
private const val OPEN_FOR_MS = 15 * 60 * 1000L

/**
 * Shares [file] with a computer on the same Wi-Fi (see [LocalShare]) while open: the address to
 * type into a browser, the same as a QR code for a tablet or another phone, and who has
 * downloaded it. Closing it stops sharing.
 */
@Composable
internal fun SendToComputerDialog(file: File, title: String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // null while looking; then the share, or nothing when the phone isn't on a local network.
    var share by remember { mutableStateOf<LocalShare?>(null) }
    var looked by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var timedOut by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        share = withContext(Dispatchers.IO) {
            LocalShare.localAddress()?.let { address ->
                runCatching { LocalShare(file, file.name, title, "application/pdf", address, onSent = { sentTo = it }) }.getOrNull()
            }
        }
        looked = true
    }
    DisposableEffect(Unit) { onDispose { share?.close() } }
    LaunchedEffect(share) {
        if (share == null) return@LaunchedEffect
        delay(OPEN_FOR_MS)
        share?.close()
        timedOut = true
    }
    val current = share

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(stringResource(R.string.document_send_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    !looked -> Unit
                    current == null -> Text(stringResource(R.string.document_send_no_wifi), color = colors.onSurfaceVariant)
                    timedOut -> Text(stringResource(R.string.document_send_stopped), color = colors.onSurfaceVariant)
                    else -> {
                        Text(stringResource(R.string.document_send_body), color = colors.onSurfaceVariant)
                        SelectionContainer {
                            Text(
                                current.url,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                            )
                        }
                        val qr = remember(current.url) { qrCode(current.url) }
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Image(
                                qr.asImageBitmap(),
                                contentDescription = stringResource(R.string.document_send_qr),
                                filterQuality = FilterQuality.None,
                                modifier = Modifier
                                    .size(168.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(Color.White)
                                    .padding(8.dp),
                            )
                        }
                        val sent = sentTo
                        if (sent == null) {
                            Text(stringResource(R.string.document_send_waiting), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                                Text(
                                    stringResource(R.string.document_send_sent, sent),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                        Text(stringResource(R.string.document_send_privacy), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (looked && current == null) {
                TextButton(onClick = {
                    looked = false
                    attempt++
                }) { Text(stringResource(R.string.document_send_retry)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.document_send_stop)) }
            }
        },
        dismissButton = {
            if (looked && current == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.document_cancel), color = colors.onSurfaceVariant) }
            }
        },
    )
}

/** [text] as a QR code, one pixel a module, black on white; the image is scaled up without smoothing. */
private fun qrCode(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
