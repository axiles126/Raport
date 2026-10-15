package com.example.report.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.report.core.TimeFmt
import com.example.report.ui.theme.ErrRed
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

fun droneScanOptions(): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Скануйте QR дрона")
    .setBeepEnabled(true)
    .setOrientationLocked(false)

/** Separate «Заїхали дрони» report: numbered list of QR scans with its own share/copy. */
@Composable
fun DroneInScreen(vm: ReportViewModel, close: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var removeAt by remember { mutableStateOf(-1) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { vm.droneArrived(it) } }
    BackHandler(onBack = close)

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ЗАЇХАЛИ ДРОНИ", Modifier.weight(1f), color = cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                val has = vm.droneIns.isNotEmpty()
                IconButton(onClick = { copyText(context, vm.droneReportText()); vm.setStatus("✓ Скопійовано", "ok") }, enabled = has) {
                    Icon(Icons.Filled.ContentCopy, "Копіювати", tint = if (has) cs.onSurface else cs.outline)
                }
                IconButton(onClick = { shareText(context, vm.droneReportText()) }, enabled = has) {
                    Icon(Icons.Filled.Share, "Поділитися", tint = if (has) cs.onSurface else cs.outline)
                }
                IconButton(onClick = { confirmClear = true }, enabled = has) {
                    Icon(Icons.Filled.DeleteSweep, "Очистити", tint = if (has) ErrRed else cs.outline)
                }
                IconButton(onClick = close) { Icon(Icons.Filled.Close, "Закрити", tint = cs.onSurface) }
            }
            HorizontalDivider(color = cs.outline)

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.droneIns.isEmpty()) item {
                    Text(
                        "Ще нічого.\nСканування QR дрона —\nнаступний номер у списку.",
                        Modifier.padding(16.dp), color = cs.outline, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                    )
                }
                itemsIndexed(vm.droneIns) { i, d ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", Modifier.width(40.dp), color = cs.onSurfaceVariant, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(TimeFmt.hm(d.t), Modifier.width(56.dp), color = cs.onSurfaceVariant, fontSize = 15.sp)
                        Text(d.code.ifEmpty { "—" }, Modifier.weight(1f), color = cs.onSurface, fontSize = 15.sp)
                        IconButton(onClick = { removeAt = i }) { Icon(Icons.Filled.Close, "Видалити", tint = cs.outline) }
                    }
                }
            }
            HorizontalDivider(color = cs.outline)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Всього: ${vm.droneIns.size}", Modifier.padding(start = 8.dp), color = cs.onSurfaceVariant, fontSize = 13.sp)
                TextButton(onClick = { scan.launch(droneScanOptions()) }) {
                    Icon(Icons.Filled.QrCodeScanner, null, tint = cs.primary)
                    Text("  СКАНУВАТИ", color = cs.primary, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        confirmButton = { TextButton(onClick = { vm.clearDroneIns(); confirmClear = false }) { Text("Очистити", color = ErrRed) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Скасувати") } },
        title = { Text("Очистити список?") },
        text = { Text("Нумерація почнеться з №1.") },
    )
    if (removeAt >= 0) AlertDialog(
        onDismissRequest = { removeAt = -1 },
        confirmButton = { TextButton(onClick = { vm.removeDroneIn(removeAt); removeAt = -1 }) { Text("Видалити", color = ErrRed) } },
        dismissButton = { TextButton(onClick = { removeAt = -1 }) { Text("Скасувати") } },
        title = { Text("Видалити №${removeAt + 1}?") },
        text = { Text("Наступні номери зсунуться.") },
    )
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Заїхали дрони", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, "Заїхали дрони")
    }
    context.startActivity(Intent.createChooser(intent, "Поділитися"))
}
