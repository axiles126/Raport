package com.example.report.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.report.core.ReportBuilder
import com.example.report.core.TimeFmt
import com.example.report.data.Flight
import com.example.report.ui.theme.ErrRed
import com.example.report.ui.theme.OkGreen
import kotlinx.coroutines.delay

/** Editor under the preview; its content depends on the report mode. */
@Composable
fun EditorSection(vm: ReportViewModel, onOpenMap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Divider(color = cs.outline)
        Spacer(Modifier.height(8.dp))
        when (vm.mode) {
            "shift" -> {
                ShiftSettings(vm)
                Spacer(Modifier.height(8.dp))
                EventsEditor(vm, onOpenMap)
            }
            "day", "night" -> {
                DayNightSummary(vm)
                Spacer(Modifier.height(8.dp))
                FieldsAndRecords(vm)
            }
            else -> FieldsAndRecords(vm)
        }
    }
}

/** «Доба» settings: shift/day/night boundaries, district code, sortie count, scouting intervals. */
@Composable
fun ShiftSettings(vm: ReportViewModel) {
    Column {
        ehead("НАЛАШТУВАННЯ ЗМІНИ")
        SettingRow("Початок зміни") {
            TimeField(vm.shiftStart) { vm.changeShiftStart(it.ifEmpty { "16:00" }) }
        }
        SettingRow("«День» з") {
            TimeField(vm.dayStart) { vm.changeDayStart(it.ifEmpty { "06:00" }) }
        }
        SettingRow("«Ніч» з") {
            TimeField(ReportBuilder.dayEndEff(vm.dayStart, vm.dayEnd)) { vm.changeDayEnd(it) }
        }
        SettingRow("Шифр району") {
            var code by remember(vm.shiftCode) { mutableStateOf(vm.shiftCode) }
            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it
                    vm.changeShiftCode(it)
                },
                modifier = Modifier.height(58.dp),
                placeholder = { Text("напр. РУБАК") },
                singleLine = true,
            )
        }
        SettingRow("Вильотів (авто)") {
            var count by remember(vm.sortieCount) { mutableStateOf(vm.sortieCount) }
            OutlinedTextField(
                value = count,
                onValueChange = {
                    count = digits(it)
                    vm.changeSortieCount(count)
                },
                modifier = Modifier.width(90.dp).height(58.dp),
                placeholder = { Text("авто") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = vm.scoutAuto, onCheckedChange = { vm.changeScoutAuto(it) })
            Text("Інтервали розвідки", fontSize = 12.sp)
        }
    }
}

/** «День»/«Ніч»: losses, damage, propellers spent and drones on position. */
@Composable
fun DayNightSummary(vm: ReportViewModel) {
    val cs = MaterialTheme.colorScheme
    ehead("ЗВЕДЕННЯ ЗМІНИ")
    Stepper("Втрати БпЛА", vm.dayCfg.lost) { vm.setDayLost(it) }
    Stepper("Пошкодження", vm.dayCfg.dmg) { vm.setDayDmg(it) }
    Spacer(Modifier.height(6.dp))

    ehead("ВИТРАЧЕНО ПРОПЕЛЕРІВ")
    vm.dayCfg.props.forEachIndexed { i, p ->
        NamedQtyRow(
            p.name,
            p.qty,
            "Тип пропелера",
            { vm.updateProp(i, it, null) },
            { vm.updateProp(i, null, it) },
            { vm.removeProp(i) },
        )
    }
    TextButton(onClick = { vm.addProp() }) {
        Text("+ Тип пропелера", color = cs.onSurfaceVariant)
    }
    Spacer(Modifier.height(6.dp))

    ehead("ДРОНІВ НА ПОЗИЦІЇ")
    vm.dayCfg.drones.forEachIndexed { i, d ->
        NamedQtyRow(
            d.name,
            d.qty,
            "Тип дрона",
            { vm.updateDrone(i, it, null) },
            { vm.updateDrone(i, null, it) },
            { vm.removeDrone(i) },
        )
    }
    TextButton(onClick = { vm.addDrone() }) {
        Text("+ Тип дрона", color = cs.onSurfaceVariant)
    }
}

/** Custom flight fields (collapsible) + the flights of the current report window. */
@Composable
fun FieldsAndRecords(vm: ReportViewModel) {
    val cs = MaterialTheme.colorScheme
    var showFields by remember { mutableStateOf(false) }
    TextButton(onClick = { showFields = !showFields }) {
        Text("Поля вильоту " + if (showFields) "▴" else "▾", color = cs.onSurfaceVariant)
    }
    if (showFields) {
        FieldsPanel(vm)
    }
    Spacer(Modifier.height(6.dp))
    if (vm.mode == "current") {
        Text(
            "Усі вильоти за 7 діб. «○ у звіт» — відкрити звіт вильоту, ✕ — видалити.",
            color = cs.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(4.dp))
    }
    val (from, to) = vm.recWindow()
    for (flight in vm.flights.filter { it.t in from until to }.sortedBy { it.t }) {
        RecordRow(vm, flight)
    }
    TextButton(onClick = { vm.addFlight() }) {
        Text("+ Додати виліт", color = cs.onSurfaceVariant)
    }
}

/** List of custom fields + form to add one (text / number / select). */
@Composable
fun FieldsPanel(vm: ReportViewModel) {
    val cs = MaterialTheme.colorScheme
    vm.fields.forEachIndexed { i, field ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val type = when (field.type) {
                "number" -> "число"
                "select" -> "список"
                else -> "текст"
            }
            val opts = if (field.opts.isEmpty()) "" else " (" + field.opts.joinToString(", ") + ")"
            Text(field.name + " · " + type + opts, Modifier.weight(1f), fontSize = 12.sp)
            TextButton(onClick = { vm.removeField(i) }) { Text("✕", color = ErrRed) }
        }
    }

    var newName by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf("text") }
    var newOpts by remember { mutableStateOf("") }
    var typeMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Назва поля") },
            singleLine = true,
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = { typeMenu = true }) {
            Text(
                when (newType) {
                    "number" -> "Число"
                    "select" -> "Список"
                    else -> "Текст"
                },
            )
        }
        DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
            for ((key, label) in listOf("text" to "Текст", "number" to "Число", "select" to "Список")) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        newType = key
                        typeMenu = false
                    },
                )
            }
        }
    }
    if (newType == "select") {
        OutlinedTextField(
            value = newOpts,
            onValueChange = { newOpts = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Варіанти через кому") },
            singleLine = true,
        )
    }
    TextButton(onClick = {
        if (newName.isNotBlank()) {
            val opts = if (newType == "select") {
                newOpts.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
            if (newType == "select" && opts.isEmpty()) {
                vm.setStatus("Вкажіть варіанти списку", "err")
            } else {
                vm.addField(newName.trim(), newType, opts)
                newName = ""
                newOpts = ""
            }
        }
    }) {
        Text("Додати поле", color = cs.onSurfaceVariant)
    }
}

/** One flight: takeoff/landing times, duration, report selection, comment and custom fields. */
@Composable
fun RecordRow(vm: ReportViewModel, flight: Flight) {
    val cs = MaterialTheme.colorScheme
    Divider(color = cs.outline)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val lt = flight.lt
            TimeField(TimeFmt.hm(flight.t), 78) {
                if (it.isNotEmpty()) vm.setFlightTakeoff(flight.t, it)
            }
            Spacer(Modifier.width(4.dp))
            TimeField(if (lt != null) TimeFmt.hm(lt) else "", 78) {
                vm.setFlightLanding(flight.t, it.ifEmpty { null })
            }
            Spacer(Modifier.width(4.dp))
            Text(
                if (lt != null) TimeFmt.fmt(lt - flight.t) else "—",
                Modifier.weight(1f),
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
            )
            if (vm.mode == "current") {
                val selected = vm.curRecT?.let { it == flight.t } ?: (flight == vm.curRec())
                TextButton(onClick = { vm.selectRec(flight.t) }) {
                    Text(
                        (if (selected) "● у звіті" else "○ у звіт") + if (flight.rep != null) " ✎" else "",
                        color = if (selected) OkGreen else cs.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                if (flight.rep != null) {
                    TextButton(onClick = { vm.clearSavedReport(flight.t) }) {
                        Text("↺", color = cs.onSurfaceVariant)
                    }
                }
            }
            TextButton(onClick = { vm.deleteFlight(flight.t) }) { Text("✕", color = ErrRed) }
        }

        // Comment: read-only field, tap opens the full-screen pad.
        var editing by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = flight.c,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                readOnly = true,
                placeholder = { Text("Коментар") },
            )
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(onClickLabel = "Редагувати коментар") { editing = true },
            )
        }
        if (editing) {
            TextPad("КОМЕНТАР · виліт " + TimeFmt.hm(flight.t), flight.c) {
                if (it != flight.c) vm.setFlightComment(flight.t, it)
                editing = false
            }
        }

        for (field in vm.fields) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(field.name, Modifier.width(96.dp), color = cs.onSurfaceVariant, fontSize = 12.sp)
                val cur = flight.x[field.id] ?: ""
                when (field.type) {
                    "number" -> {
                        val n = cur.toIntOrNull() ?: 0
                        TextButton(onClick = {
                            val m = (n - 1).coerceAtLeast(0)
                            vm.setFlightField(flight.t, field.id, if (m == 0) "" else m.toString())
                        }) { Text("−", fontSize = 18.sp) }
                        Text(n.toString(), Modifier.width(28.dp), textAlign = TextAlign.Center)
                        TextButton(onClick = {
                            vm.setFlightField(flight.t, field.id, (n + 1).toString())
                        }) { Text("+", fontSize = 18.sp) }
                    }
                    "select" -> {
                        var open by remember { mutableStateOf(false) }
                        TextButton(onClick = { open = true }) { Text(cur) }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(
                                text = { Text("—") },
                                onClick = {
                                    vm.setFlightField(flight.t, field.id, "")
                                    open = false
                                },
                            )
                            for (opt in field.opts) {
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        vm.setFlightField(flight.t, field.id, opt)
                                        open = false
                                    },
                                )
                            }
                        }
                    }
                    else -> {
                        var value by remember(flight.t, cur) { mutableStateOf(cur) }
                        OutlinedTextField(
                            value = value,
                            onValueChange = {
                                value = it
                                vm.setFlightField(flight.t, field.id, it)
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                }
            }
        }
    }
}

/** «Доба»: events of the current shift (time range, description, MGRS picked from the map). */
@Composable
fun EventsEditor(vm: ReportViewModel, onOpenMap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ehead("ПОДІЇ ЗМІНИ")
    val shiftFrom = ReportBuilder.shiftBounds(System.currentTimeMillis(), vm.shiftStart).first
    val (from, to) = ReportBuilder.shiftBounds(System.currentTimeMillis(), vm.shiftStart)
    val inShift = vm.events.withIndex()
        .filter { it.value.t in from until to }
        .map { it.index to it.value }
        .sortedBy { it.second.t }
    for ((i, event) in inShift) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeField(TimeFmt.hm(event.t), 78) { s ->
                    if (s.isNotEmpty()) {
                        var ts = TimeFmt.tsFromTime(shiftFrom, s)
                        if (ts < shiftFrom) ts += vm.DAY
                        vm.updateEvent(i) { it.copy(t = ts) }
                    }
                }
                Spacer(Modifier.width(4.dp))
                val t2 = event.t2
                TimeField(if (t2 != null) TimeFmt.hm(t2) else "", 78) { s ->
                    if (s.isEmpty()) {
                        vm.updateEvent(i) { it.copy(t2 = null) }
                    } else {
                        var ts = TimeFmt.tsFromTime(shiftFrom, s)
                        if (ts < event.t) ts += vm.DAY
                        vm.updateEvent(i) { it.copy(t2 = ts) }
                    }
                }
                TextButton(onClick = { vm.removeEvent(i) }) { Text("✕", color = ErrRed) }
            }
            var text by remember(event.text) { mutableStateOf(event.text) }
            OutlinedTextField(
                value = text,
                onValueChange = { s ->
                    text = s
                    vm.updateEvent(i) { it.copy(text = s) }
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Опис події") },
                singleLine = true,
            )
            var mgrs by remember(event.mgrs) { mutableStateOf(event.mgrs) }
            OutlinedTextField(
                value = mgrs,
                onValueChange = { s ->
                    mgrs = s
                    vm.updateEvent(i) { it.copy(mgrs = s.trim()) }
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Координати (MGRS)") },
                trailingIcon = {
                    IconButton(onClick = {
                        vm.eventCoordTarget = i
                        onOpenMap()
                    }) {
                        Icon(Icons.Filled.Place, "З карти", tint = cs.onSurface)
                    }
                },
                singleLine = true,
            )
        }
    }
    TextButton(onClick = { vm.addEvent("") }) {
        Text("+ Додати подію", color = cs.onSurfaceVariant)
    }
}

/** Full-screen plain-text editor dialog; [onDone] receives the text on ✓ or dismiss. */
@Composable
fun TextPad(title: String, initial: String, onDone: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = { onDone(value.text) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        Modifier.weight(1f),
                        color = cs.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    IconButton(onClick = { onDone(value.text) }) {
                        Icon(Icons.Filled.Check, "Готово", tint = OkGreen)
                    }
                }
                HorizontalDivider(color = cs.outline)
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .focusRequester(focus),
                    textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp, lineHeight = 24.sp),
                    cursorBrush = SolidColor(cs.onSurface),
                )
            }
        }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            delay(150)
            try {
                focus.requestFocus()
                keyboard?.show()
            } catch (_: Throwable) {
            }
        }
    }
}
