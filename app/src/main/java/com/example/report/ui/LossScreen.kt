package com.example.report.ui
import com.example.report.ui.theme.OkGreen
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.report.core.Loss
import com.example.report.core.TimeFmt
import com.example.report.ui.theme.ErrRed

/**
 * Full-screen drone-loss report: cause, coordinates (with map picker), pilot, drone and the
 * ready-to-send message. Shown while [ReportViewModel.lossDraft] is set.
 */
@Composable
fun LossScreen(
    vm: ReportViewModel,
    share: () -> Unit,
    copy: () -> Unit,
    openMap: () -> Unit,
    close: () -> Unit,
) {
    val draft = vm.lossDraft ?: return
    val cs = MaterialTheme.colorScheme
    var pickDrone by remember { mutableStateOf(false) }
    if (pickDrone) {
        AlertDialog(
            onDismissRequest = { pickDrone = false },
            title = { Text("Дрон зі списку") },
            text = {
                var q by remember { mutableStateOf("") }
                Column {
                    val all = vm.droneIns.reversed()
                    if (all.size > 5) OutlinedTextField(
                        value = q, onValueChange = { q = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Пошук: модель, цифри, номер") },
                        singleLine = true,
                    )
                    val shown = if (q.isBlank()) all else all.filter {
                        val t = q.trim()
                        it.model.contains(t, true) || it.tail.contains(t, true) || it.code.contains(t, true)
                    }
                    if (all.isEmpty()) Text("Список «Заїхали / Передані» порожній — введи вручну.", color = cs.outline)
                    else if (shown.isEmpty()) Text("Нічого не знайдено.", color = cs.outline)
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (d in shown) TextButton(onClick = {
                        vm.updateLoss(drone = d.model, tail = d.tail, serial = d.code); pickDrone = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("${d.model} (${d.tail.ifEmpty { "    " }}) ${d.code}" + if (d.kind == DRONE_OUT) " · переданий" else "",
                            color = cs.onSurface, modifier = Modifier.fillMaxWidth())
                    }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickDrone = false }) { Text("Закрити") } },
        )
    }
    BackHandler(onBack = close)

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "ВТРАТА БОРТУ · " + TimeFmt.hm(draft.at),
                    Modifier.weight(1f),
                    color = ErrRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = copy) {
                    Icon(Icons.Filled.ContentCopy, "Копіювати", tint = cs.onSurface)
                }
                IconButton(onClick = share) {
                    Icon(Icons.Filled.Share, "Поділитися", tint = cs.onSurface)
                }
                IconButton(onClick = close) {
                    Icon(Icons.Filled.Close, "Закрити", tint = cs.onSurface)
                }
            }
            HorizontalDivider(color = cs.outline)

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text("ПРИЧИНА", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (cause in Loss.CAUSES) {
                        val sel = cause == draft.cause
                        OutlinedButton(
                            onClick = { changeLossCause(vm, cause) },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(if (sel) 2.dp else 1.dp, if (sel) ErrRed else cs.outline),
                        ) {
                            Text(
                                Loss.label(cause),
                                color = if (sel) ErrRed else cs.onSurface,
                                fontSize = 12.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val custom = Loss.isCustom(draft.cause)
                OutlinedButton(
                    onClick = { if (!custom) changeLossCause(vm, Loss.CUSTOM) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(if (custom) 2.dp else 1.dp, if (custom) ErrRed else cs.outline),
                ) {
                    Text(
                        "СВОЯ ПРИЧИНА",
                        color = if (custom) ErrRed else cs.onSurface,
                        fontSize = 12.sp,
                        fontWeight = if (custom) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                if (custom) {
                    Spacer(Modifier.height(6.dp))
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    OutlinedTextField(
                        value = Loss.customText(draft.cause),
                        onValueChange = { changeLossCause(vm, Loss.CUSTOM + it.replace("\n", " ").take(120)) },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        label = { Text("Причина втрати") },
                        placeholder = { Text("напр. технічної несправності") },
                        supportingText = { Text("У звіті: «Втрата дрону внаслідок …»") },
                        singleLine = true,
                    )
                    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft.coords,
                    onValueChange = { vm.updateLoss(coords = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Орієнтовні координати (MGRS)") },
                    trailingIcon = {
                        IconButton(onClick = openMap) {
                            Icon(Icons.Filled.Place, "З карти", tint = cs.onSurface)
                        }
                    },
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = draft.pilot,
                    onValueChange = { vm.updateLoss(pilot = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Пілот") },
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = draft.surname,
                    onValueChange = { vm.updateLoss(surname = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Прізвище пілота") },
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                // Drone: model (4 digits) number — from the «Заїхали/Передані» list or typed by hand (no QR: the drone is lost)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ДРОН", Modifier.weight(1f), color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { pickDrone = true }, border = BorderStroke(1.dp, OkGreen)) {
                        Icon(Icons.AutoMirrored.Filled.List, null, tint = OkGreen)
                        Spacer(Modifier.width(6.dp))
                        Text("ВИБРАТИ ЗІ СПИСКУ", color = OkGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedTextField(
                    value = draft.drone,
                    onValueChange = { vm.updateLoss(drone = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Модель") },
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    OutlinedTextField(
                        value = draft.tail,
                        onValueChange = { vm.updateLoss(tail = it) },
                        modifier = Modifier.weight(0.38f),
                        label = { Text("4 цифри") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value = draft.serial,
                        onValueChange = { vm.updateLoss(serial = it) },
                        modifier = Modifier.weight(0.62f),
                        label = { Text("Номер") },
                        singleLine = true,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("ПОВІДОМЛЕННЯ", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Surface(Modifier.fillMaxWidth(), color = cs.surfaceVariant) {
                    SelectionContainer {
                        Text(
                            vm.lossMessage(),
                            Modifier.padding(12.dp),
                            style = TextStyle(color = cs.onSurface, fontSize = 15.sp, lineHeight = 22.sp),
                        )
                    }
                }
            }

            HorizontalDivider(color = cs.outline)
            Text(
                "Посадку відмічено · втрата піде у звіт «День/Ніч» за часом втрати" +
                    (if (draft.decName.isNotEmpty()) " · «Дронів на позиції» ${draft.decName}: −1" else ""),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * Switches the loss cause; the "HH:MM Втрата дрону …" line already written into the flight
 * comment is rewritten to the new cause.
 * TODO(decomp): inlined into the cause button's onClick in the APK (no VM method).
 */
private fun changeLossCause(vm: ReportViewModel, cause: String) {
    val d = vm.lossDraft ?: return
    if (d.cause == cause) return
    vm.flights.firstOrNull { it.t == d.flightT }?.let { f ->
        val old = TimeFmt.hm(d.at) + " " + Loss.line(d.cause)
        val new = TimeFmt.hm(d.at) + " " + Loss.line(cause)
        if (f.c.contains(old)) vm.setFlightComment(f.t, f.c.replace(old, new))
    }
    vm.lossDraft = d.copy(cause = cause)
}
