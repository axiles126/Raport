package com.example.report.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.example.report.core.Coords
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.report.core.TimeFmt
import com.example.report.data.StrikeDraft
import com.example.report.ui.theme.OkGreen

/** Separate «Ураження» report: form + live preview + share. Coordinates can be taken from the map. */
@Composable
fun StrikeScreen(vm: ReportViewModel, openMap: () -> Unit, close: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    BackHandler(onBack = close)
    LaunchedEffect(Unit) { if (vm.strike.t == 0L) vm.newStrike() }
    val s = vm.strike
    fun upd(f: (StrikeDraft) -> StrikeDraft) = vm.updateStrike(f)
    var pickFromFlight by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("УРАЖЕННЯ", Modifier.weight(1f), color = cs.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { vm.newStrike() }) { Text("Нове", color = cs.onSurfaceVariant) }
                IconButton(onClick = close) { Icon(Icons.Filled.Close, "Закрити", tint = cs.onSurface) }
            }
            HorizontalDivider(color = cs.outline)

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(TimeFmt.fmtD4(s.t.takeIf { it > 0 } ?: System.currentTimeMillis()), color = cs.onSurface)
                    Spacer(Modifier.width(10.dp))
                    TimeField(TimeFmt.hm(s.t.takeIf { it > 0 } ?: System.currentTimeMillis()), 84) { hm ->
                        if (hm.isNotEmpty()) upd { it.copy(t = TimeFmt.tsFromTime(it.t.takeIf { t -> t > 0 } ?: System.currentTimeMillis(), hm)) }
                    }
                    TextButton(onClick = { upd { it.copy(t = System.currentTimeMillis()) } }) { Text("Зараз") }
                }
                Field("Екіпаж", s.crew, vm.name.uppercase()) { v -> upd { it.copy(crew = v) } }
                Field("Позиція", s.pos, vm.name.uppercase()) { v -> upd { it.copy(pos = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Field("Ціль", s.target) { v -> upd { it.copy(target = v) } } }
                    TextButton(onClick = { pickFromFlight = true }) { Text("З вильоту", color = OkGreen, maxLines = 1, softWrap = false) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Field("Координати", s.coords) { v -> upd { it.copy(coords = v) } } }
                    TextButton(onClick = { vm.strikeCoordTarget = true; openMap() }) {
                        Icon(Icons.Filled.Place, null, tint = OkGreen)
                        Text(" З карти", color = OkGreen, maxLines = 1, softWrap = false)
                    }
                }
                Field("БК (кожен рядок — пункт)", s.bk, single = false) { v -> upd { it.copy(bk = v) } }
                Field("Стрім", s.stream) { v -> upd { it.copy(stream = v) } }
                Field("Пілот", s.pilot, vm.leader) { v -> upd { it.copy(pilot = v) } }
                Field("Результат", s.result) { v -> upd { it.copy(result = v) } }
                Spacer(Modifier.height(10.dp))
                Text(vm.strikeText(), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
            HorizontalDivider(color = cs.outline)
            if (pickFromFlight) FlightTargetPicker(vm, { t, m ->
                upd { it.copy(target = t, coords = m.ifEmpty { it.coords }) }; pickFromFlight = false
            }) { pickFromFlight = false }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { shareStrike(context, vm.strikeText()) }) {
                    Icon(Icons.Filled.Share, null, tint = OkGreen)
                    Text(" ПОДІЛИТИСЯ", color = OkGreen, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Lines of the active (or last) flight: target text + its coordinates, newest first. */
private fun flightTargets(vm: ReportViewModel): List<Pair<String, String>> {
    val rec = vm.activeRec() ?: vm.flights.maxByOrNull { it.t } ?: return emptyList()
    return rec.c.split("\n").mapNotNull { raw ->
        val noTime = Coords.TIME_RE.replace(raw.trim(), "")
        if (noTime.isEmpty() || Coords.isCoord(noTime) || Coords.isLand(noTime)) return@mapNotNull null
        val mgrs = Regex("за координатами\\s*(.+)$", RegexOption.IGNORE_CASE).find(noTime)?.groupValues?.get(1)?.trim() ?: ""
        val text = Coords.COUNT_RE.replace(Coords.COORD_RE.replace(noTime, ""), "").trim()
        if (text.equals("загальна розвідка", true)) null else text to mgrs.ifEmpty { rec.mgrs }
    }.reversed()
}

@Composable
private fun FlightTargetPicker(vm: ReportViewModel, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val items = flightTargets(vm)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ціль з вильоту") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (items.isEmpty()) Text("У вильоті ще немає відміток.", color = MaterialTheme.colorScheme.outline)
                for ((t, m) in items) {
                    TextButton(onClick = { onPick(t, m) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(t, color = MaterialTheme.colorScheme.onSurface)
                            Text(m.ifEmpty { "без координат" }, fontSize = 12.sp, color = if (m.isEmpty()) MaterialTheme.colorScheme.outline else OkGreen)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
    )
}

@Composable
private fun Field(label: String, value: String, hint: String = "", single: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        placeholder = if (hint.isNotEmpty()) ({ Text(hint) }) else null,
        singleLine = single,
        minLines = if (single) 1 else 2,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

private fun shareStrike(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, "Ураження")
    }
    context.startActivity(Intent.createChooser(intent, "Поділитися"))
}
