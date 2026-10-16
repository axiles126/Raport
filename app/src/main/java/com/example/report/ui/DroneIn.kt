package com.example.report.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.report.ui.theme.ErrRed
import com.example.report.ui.theme.OkGreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

const val DRONE_IN = "in"
const val DRONE_OUT = "out"

/** Entry being added (index = -1) or edited. */
data class DroneDraft(val index: Int, val kind: String, val model: String, val tail: String, val code: String)

fun droneScanOptions(): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Скануйте QR дрона")
    .setBeepEnabled(true)
    .setOrientationLocked(false)
    .setCaptureActivity(com.example.report.scan.ScanActivity::class.java)

private fun kindTitle(kind: String) = if (kind == DRONE_OUT) "Переданий дрон" else "Заїхав дрон"

/** Model + 4 digits in brackets + number (from QR or typed). */
@Composable
fun DroneEntryDialog(vm: ReportViewModel, draft: DroneDraft, onDone: () -> Unit) {
    var model by remember(draft) { mutableStateOf(draft.model) }
    var tail by remember(draft) { mutableStateOf(draft.tail) }
    var code by remember(draft) { mutableStateOf(draft.code) }
    val context = LocalContext.current
    val rescan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { code = it.trim() } }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(kindTitle(draft.kind) + if (draft.index < 0) "" else " · правка") },
        text = {
            Column {
                OutlinedTextField(model, { model = it }, label = { Text("Модель") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    tail, { v -> tail = v.filter { it.isDigit() }.take(4) },
                    label = { Text("4 цифри (в дужках)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    code, { code = it }, label = { Text("Номер (QR або вручну)") }, singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { rescan.launch(droneScanOptions()) }) { Icon(Icons.Filled.QrCodeScanner, "Сканувати") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${model.trim()} (${tail.ifEmpty { "    " }}) ${code.trim()}",
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = {
                    vm.saveDrone(draft.index, draft.kind, model, tail, code)
                    shareText(context, vm.droneReportText(draft.kind)); onDone()
                }) { Text("Зберегти й поділитися", color = OkGreen, fontWeight = FontWeight.Bold) }
                Row {
                    TextButton(onClick = onDone) { Text("Скасувати") }
                    TextButton(onClick = { vm.saveDrone(draft.index, draft.kind, model, tail, code); onDone() }) {
                        Text("Зберегти", color = OkGreen)
                    }
                }
            }
        },
    )
}

/** Separate «Заїхали / Передані дрони» reports: two numbered lists, each with its own share/copy. */
@Composable
fun DroneInScreen(vm: ReportViewModel, startKind: String = DRONE_IN, close: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var kind by remember { mutableStateOf(startKind) }
    var confirmClear by remember { mutableStateOf(false) }
    var removeAt by remember { mutableStateOf(-1) }
    var draft by remember { mutableStateOf<DroneDraft?>(null) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { draft = DroneDraft(-1, kind, vm.droneModel, "", it.trim()) }
    }
    BackHandler(onBack = close)
    val list = vm.dronesOf(kind)
    val text = vm.droneReportText(kind)

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((k, label) in listOf(DRONE_IN to "ЗАЇХАЛИ", DRONE_OUT to "ПЕРЕДАНІ")) {
                    TextButton(onClick = { kind = k }) {
                        Text(
                            label + " · " + vm.dronesOf(k).size, maxLines = 1, softWrap = false,
                            color = if (kind == k) cs.onSurface else cs.outline,
                            fontWeight = if (kind == k) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = close) { Icon(Icons.Filled.Close, "Закрити", tint = cs.onSurface) }
            }
            HorizontalDivider(color = cs.outline)

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (list.isEmpty()) item {
                    Text("Ще нічого.\nСкануйте QR або додайте вручну.", Modifier.padding(16.dp), color = cs.outline, fontFamily = FontFamily.Monospace)
                }
                items(list.size) { n ->
                    val (idx, d) = list[n]
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { draft = DroneDraft(idx, d.kind, d.model, d.tail, d.code) }
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${n + 1}.", Modifier.width(36.dp), color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        Text(
                            "${d.model} (${d.tail.ifEmpty { "    " }}) ${d.code}", Modifier.weight(1f),
                            color = if (d.tail.isEmpty()) ErrRed else cs.onSurface, fontSize = 15.sp,
                        )
                        IconButton(onClick = { removeAt = idx }) { Icon(Icons.Filled.Close, "Видалити", tint = cs.outline) }
                    }
                }
            }
            HorizontalDivider(color = cs.outline)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val has = list.isNotEmpty()
                TextButton(onClick = { shareText(context, text) }, enabled = has) {
                    Icon(Icons.Filled.Share, null, tint = if (has) OkGreen else cs.outline)
                    Text(" ПОДІЛИТИСЯ", color = if (has) OkGreen else cs.outline, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                IconButton(onClick = { confirmClear = true }, enabled = has) {
                    Icon(Icons.Filled.DeleteSweep, "Очистити", tint = if (has) ErrRed else cs.outline)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { draft = DroneDraft(-1, kind, vm.droneModel, "", "") }) {
                    Icon(Icons.Filled.Add, "Вручну", tint = cs.primary)
                }
                TextButton(onClick = { scan.launch(droneScanOptions()) }) {
                    Icon(Icons.Filled.QrCodeScanner, null, tint = cs.primary)
                    Text(" QR", color = cs.primary, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }

    draft?.let { DroneEntryDialog(vm, it) { draft = null } }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        confirmButton = { TextButton(onClick = { vm.clearDrones(kind); confirmClear = false }) { Text("Очистити", color = ErrRed) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Скасувати") } },
        title = { Text("Очистити список?") },
        text = { Text("Нумерація почнеться з №1.") },
    )
    if (removeAt >= 0) AlertDialog(
        onDismissRequest = { removeAt = -1 },
        confirmButton = { TextButton(onClick = { vm.removeDroneEntry(removeAt); removeAt = -1 }) { Text("Видалити", color = ErrRed) } },
        dismissButton = { TextButton(onClick = { removeAt = -1 }) { Text("Скасувати") } },
        title = { Text("Видалити запис?") },
        text = { Text("Наступні номери зсунуться.") },
    )
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, "Дрони")
    }
    context.startActivity(Intent.createChooser(intent, "Поділитися"))
}
