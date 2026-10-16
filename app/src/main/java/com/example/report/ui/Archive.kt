package com.example.report.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.report.core.ReportBuilder
import com.example.report.core.TimeFmt
import com.example.report.data.Flight
import com.example.report.ui.theme.OkGreen

private val ARCH_MODES = listOf("day" to "ДЕНЬ", "night" to "НІЧ", "shift" to "ДОБА")

/**
 * Report archive: pick a date (last [ReportViewModel.KEEP_DAYS] days) and День / Ніч / Доба;
 * shows the report built from that period's sorties (editable, edits kept) and the sorties
 * themselves — each can be fixed or sent as its own report.
 */
@Composable
fun ArchiveScreen(vm: ReportViewModel, close: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    BackHandler(onBack = close)
    val days = remember { vm.archiveDays() }
    var day by remember { mutableStateOf(days.first()) }
    var mode by remember { mutableStateOf(if (vm.mode in listOf("day", "night", "shift")) vm.mode else "day") }
    var flightRep by remember { mutableStateOf<Flight?>(null) }

    val text = vm.archiveText(mode, day)
    val edited = vm.archEdits.containsKey(vm.archKey(mode, day))
    val title = ARCH_MODES.first { it.first == mode }.second + " · " + TimeFmt.fmtD(day)

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("АРХІВ ЗВІТІВ", Modifier.weight(1f), color = cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (edited) IconButton(onClick = { vm.resetArchive(mode, day) }) {
                    Icon(Icons.Filled.Refresh, "Повернути автоматичний", tint = cs.onSurface)
                }
                IconButton(onClick = { copyText(ctx, vm, ReportBuilder.finalize(mode, text)) }) {
                    Icon(Icons.Filled.ContentCopy, "Копіювати", tint = cs.onSurface)
                }
                IconButton(onClick = { shareText(ctx, ReportBuilder.finalize(mode, text), title) }) {
                    Icon(Icons.Filled.Share, "Поділитися", tint = cs.onSurface)
                }
                IconButton(onClick = close) { Icon(Icons.Filled.Close, "Закрити", tint = cs.onSurface) }
            }
            HorizontalDivider(color = cs.outline)

            // dates
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
                for (d in days) {
                    val n = vm.flights.count { it.t >= d && it.t < d + 86_400_000L }
                    val sel = d == day
                    OutlinedButton(
                        onClick = { day = d },
                        border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) OkGreen else cs.outline),
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text(
                            TimeFmt.fmtD(d).take(5) + if (n > 0) " · $n" else "",
                            color = if (sel) OkGreen else if (n > 0) cs.onSurface else cs.outline,
                            fontSize = 12.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
            // День / Ніч / Доба
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, label) in ARCH_MODES) {
                    val sel = k == mode
                    OutlinedButton(
                        onClick = { mode = k },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) OkGreen else cs.outline),
                    ) { Text(label, color = if (sel) OkGreen else cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
            HorizontalDivider(color = cs.outline, modifier = Modifier.padding(top = 6.dp))

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(title, color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Surface(color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = text,
                        onValueChange = { vm.editArchive(mode, day, it) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(10.dp),
                        textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(cs.onSurface),
                        decorationBox = { inner -> Box { inner() } },
                    )
                }
                Text(
                    if (edited) "Правлено вручну · зміни у вильотах підтягуються · ↺ — автоматичний"
                    else "Автоматичний звіт за вильотами періоду · можна правити",
                    color = if (edited) OkGreen else cs.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                val list = vm.archiveFlights(mode, day)
                Spacer(Modifier.height(8.dp))
                Text("ВИЛЬОТИ (${list.size})", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (list.isEmpty()) Text("За цей період вильотів немає.", color = cs.outline, fontSize = 12.sp)
                for (f in list) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text(
                            "Виліт " + TimeFmt.hm(f.t) + " – " + (f.lt?.let { TimeFmt.hm(it) } ?: "…") + if (f.rep != null) " ✎" else "",
                            Modifier.weight(1f), color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        )
                        TextButton(onClick = { flightRep = f }) { Text("ЗВІТ ВИЛЬОТУ", color = OkGreen, fontSize = 12.sp) }
                    }
                    RecordRow(vm, f)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    flightRep?.let { f0 ->
        val f = vm.flights.firstOrNull { it.t == f0.t } ?: f0
        var t by remember(f.t) { mutableStateOf(vm.flightReport(f)) }
        AlertDialog(
            onDismissRequest = { flightRep = null },
            title = { Text("Звіт вильоту " + TimeFmt.hm(f.t)) },
            text = {
                OutlinedTextField(
                    value = t, onValueChange = { t = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp),
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.setFlightRep(f.t, t); copyText(ctx, vm, t) }) { Text("Копіювати") }
                    TextButton(onClick = { vm.setFlightRep(f.t, t); shareText(ctx, t, "Звіт вильоту"); flightRep = null }) { Text("Поділитися") }
                }
            },
            dismissButton = {
                Row {
                    if (f.rep != null) TextButton(onClick = { vm.setFlightRep(f.t, null); t = vm.flightReport(f.copy(rep = null)) }) { Text("↺") }
                    TextButton(onClick = { if (t != vm.flightReport(f)) vm.setFlightRep(f.t, t); flightRep = null }) { Text("Зберегти") }
                }
            },
        )
    }
}

private fun copyText(ctx: Context, vm: ReportViewModel, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(com.example.report.core.sensitiveClip(ClipData.newPlainText("Звіт", text)))
    vm.setStatus("✓ Скопійовано", "ok")
}

private fun shareText(ctx: Context, text: String, subject: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(i, "Поділитися звітом").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
