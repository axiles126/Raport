package com.example.report.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.report.auto.PilotWatchService
import com.example.report.core.ReportBuilder
import com.example.report.core.TimeFmt
import com.example.report.data.ScheduledSend
import com.example.report.data.Store
import com.example.report.sync.NetUtil
import com.example.report.ui.theme.ErrRed
import com.example.report.ui.theme.OkGreen
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

private val HM_RE = Regex("^\\d{1,2}:\\d{2}$")

/** Nearest pending alarm: the one-shot one or the next daily one, whichever comes first (0 = none). */
private fun nearestAlarm(vm: ReportViewModel): Long {
    var at = if (vm.alarmAt > System.currentTimeMillis()) vm.alarmAt else 0L
    val nextDaily = vm.nextDaily()
    if (at == 0L || (nextDaily != 0L && at >= nextDaily)) at = nextDaily
    return at
}

// ==== Settings ===================================================================================

@Composable
fun SettingsDialog(vm: ReportViewModel, dismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var showAlarm by remember { mutableStateOf(false) }
    if (showAlarm) {
        AlarmDialog(vm) { showAlarm = false }
        return
    }
    AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = { TextButton(onClick = dismiss) { Text("Готово") } },
        title = { Text("Налаштування") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val next = nearestAlarm(vm)

                // ---- alarm
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.NotificationsActive,
                        contentDescription = "Будильник",
                        tint = if (next != 0L) OkGreen else cs.onSurface,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Будильник", fontSize = 14.sp)
                        Text(
                            if (next != 0L) "Найближчий о " + TimeFmt.hm(next) else "Не встановлено",
                            color = cs.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    TextButton(onClick = { showAlarm = true }) { Text("Відкрити") }
                }
                HorizontalDivider(color = cs.outline)
                Spacer(Modifier.height(12.dp))

                SecuritySection()
                HorizontalDivider(color = cs.outline)
                Spacer(Modifier.height(12.dp))

                // ---- report periods
                Text(
                    "ЧАС ЗВІТІВ",
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "За який проміжок складається звіт. Формат ГГ:ХХ або 0600.",
                    Modifier.padding(top = 2.dp, bottom = 4.dp),
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                val dayEndEff = ReportBuilder.dayEndEff(vm.dayStart, vm.dayEnd)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Доба", Modifier.width(52.dp), fontSize = 14.sp)
                    Text("з ", fontSize = 13.sp)
                    TimeBox(vm.shiftStart) { vm.changeShiftStart(it) }
                    Text("  · 24 год", color = cs.onSurfaceVariant, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("День", Modifier.width(52.dp), fontSize = 14.sp)
                    Text("з ", fontSize = 13.sp)
                    TimeBox(vm.dayStart) { vm.changeDayStart(it) }
                    Text(" до ", fontSize = 13.sp)
                    TimeBox(dayEndEff) { vm.changeDayEnd(it) }
                }
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ніч", Modifier.width(52.dp), fontSize = 14.sp)
                    Text(
                        "з " + dayEndEff + " до " + vm.dayStart,
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                HorizontalDivider(color = cs.outline)
                Spacer(Modifier.height(12.dp))

                // ---- DJI Pilot 2 auto takeoff/landing (only when DJI Pilot 2 is installed)
                AutoDjiSection()

                // ---- sending after landing
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = vm.autoSend, onCheckedChange = { vm.changeAutoSend(it) })
                    Text("Автоматично надсилати звіт", fontSize = 14.sp)
                }
                Text(
                    "Після кожної посадки звіт «Зараз» одразу відкривається у вікні «Поділитися».",
                    Modifier.padding(start = 4.dp, top = 2.dp),
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = vm.autoWhatsApp, onCheckedChange = { vm.changeAutoWhatsApp(it) })
                    Text("Надсилати у WhatsApp", fontSize = 14.sp)
                }
                if (vm.autoWhatsApp) {
                    OutlinedTextField(
                        value = vm.waPhone,
                        onValueChange = { vm.changeWaPhone(it) },
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        label = { Text("Номер (напр. 380671234567)") },
                        singleLine = true,
                    )
                    Text(
                        "Із номером — одразу відкриється чат із цим контактом і готовим звітом. Без номера — вибір контакту в WhatsApp.",
                        Modifier.padding(start = 4.dp, top = 2.dp),
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = vm.autoSignal, onCheckedChange = { vm.changeAutoSignal(it) })
                    Text("Надсилати у Signal", fontSize = 14.sp)
                }
                Text(
                    "Після посадки відкриється Signal з готовим звітом — оберіть отримувача й надішліть.",
                    Modifier.padding(start = 4.dp, top = 2.dp),
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                if (vm.autoWhatsApp || vm.autoSignal) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { vm.sendDirect() }) { Text("Надіслати тест зараз") }
                    Text(
                        "WhatsApp/Signal не дозволяють надсилати без вікна — застосунок відкривається з готовим текстом, лишається натиснути «Надіслати».",
                        Modifier.padding(top = 2.dp),
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(16.dp))

                // ---- scheduled reports
                Text(
                    "РОЗКЛАД АВТОЗВІТІВ У ГРУПИ",
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "У вказаний час прийде сповіщення з готовим звітом (за даними вильотів). Тап — відкриє застосунок, лишиться обрати групу й надіслати.",
                    Modifier.padding(top = 2.dp, bottom = 4.dp),
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                for (s in vm.schedules) ScheduleRow(vm, s)
            }
        },
    )
}

// ShiftSettings lives in Editor.kt (its lambdas belong to EditorKt).

// ==== Alarm ======================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmDialog(vm: ReportViewModel, dismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val next = nearestAlarm(vm)
    AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = { TextButton(onClick = dismiss) { Text("Закрити") } },
        dismissButton = {
            if (vm.alarmAt > 0) {
                TextButton(onClick = {
                    vm.cancelAlarm()
                    dismiss()
                }) { Text("Вимкнути") }
            }
        },
        title = { Text("Будильник") },
        text = {
            Column {
                val info = if (next != 0L) {
                    val left = ((next - System.currentTimeMillis()).coerceAtLeast(0L) + 500) / 1000
                    "Найближчий о " + TimeFmt.hm(next) + " · через " +
                        TimeFmt.pad2((left / 3600).toInt()) + ":" +
                        TimeFmt.pad2((left / 60 % 60).toInt()) + ":" +
                        TimeFmt.pad2((left % 60).toInt())
                } else {
                    "Не встановлено"
                }
                Text(info, color = cs.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))

                Text("ЩОДЕННО", color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                vm.daily.forEachIndexed { i, d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = d.on, onCheckedChange = { vm.setDailyOn(i, it) })
                        var t by remember(d.t) { mutableStateOf(d.t) }
                        OutlinedTextField(
                            value = t,
                            onValueChange = {
                                t = it
                                if (HM_RE.matches(it)) vm.setDailyTime(i, it)
                            },
                            modifier = Modifier.height(56.dp),
                            placeholder = { Text("HH:MM") },
                            singleLine = true,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                Text("РАЗОВО", color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        5 to "5 хв",
                        10 to "10 хв",
                        15 to "15 хв",
                        30 to "30 хв",
                        60 to "60 хв",
                        120 to "2 год",
                    ).forEach { (minutes, label) ->
                        TextButton(onClick = {
                            vm.setAlarmIn(minutes)
                            dismiss()
                        }) { Text(label, fontSize = 12.sp) }
                    }
                }
            }
        },
    )
}

/** One scheduled group report: enabled, time, target messenger, group name, test send. */
@Composable
fun ScheduleRow(vm: ReportViewModel, s: ScheduledSend) {
    val label = when (s.mode) {
        "day" -> "День"
        "night" -> "Ніч"
        "shift" -> "Доба"
        else -> "Звіт"
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = s.enabled,
                onCheckedChange = { on -> vm.updateSchedule(s.id) { it.copy(enabled = on) } },
            )
            Text(label, Modifier.width(56.dp), fontSize = 14.sp)
            var time by remember(s.time) { mutableStateOf(s.time) }
            OutlinedTextField(
                value = time,
                onValueChange = { v ->
                    time = v
                    if (HM_RE.matches(v)) vm.updateSchedule(s.id) { it.copy(time = v) }
                },
                modifier = Modifier.width(96.dp),
                placeholder = { Text("HH:MM") },
                singleLine = true,
            )
            TextButton(onClick = {
                vm.updateSchedule(s.id) { it.copy(app = if (it.app == "signal") "whatsapp" else "signal") }
            }) {
                Text(if (s.app == "signal") "Signal" else "WhatsApp", fontSize = 12.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            var group by remember(s.group) { mutableStateOf(s.group) }
            OutlinedTextField(
                value = group,
                onValueChange = { v ->
                    group = v
                    vm.updateSchedule(s.id) { it.copy(group = v) }
                },
                modifier = Modifier.weight(1f),
                label = { Text("Назва групи (нагадування)") },
                singleLine = true,
            )
            TextButton(onClick = { vm.testSchedule(s) }) { Text("Тест") }
        }
    }
}

// ==== DJI Pilot 2 auto takeoff/landing ===========================================================

// TODO(decomp): these two checks were PilotWatchService.Companion helpers inlined into AutoDjiSection.
private fun djiPilotInstalled(context: Context): Boolean =
    runCatching { context.packageManager.getPackageInfo("com.dji.industry.pilot", 0) }.isSuccess

private fun pilotWatchEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val me = context.packageName + "/" + PilotWatchService::class.java.name
    // TODO(decomp): bytecode calls StringsJVM.equals(a, b); ignoreCase flag not recoverable — assumed true.
    return enabled.split(':').any { it.equals(me, ignoreCase = true) }
}

@Composable
fun AutoDjiSection() {
    val context = LocalContext.current
    val installed = remember { djiPilotInstalled(context) }
    if (!installed) return

    val cs = MaterialTheme.colorScheme
    val store = remember { Store(context) }
    var autoDji by remember { mutableStateOf(store.sp.getBoolean("kap_autodji", true)) }
    // Re-checks the service state / last DJI status once a second.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val serviceOn = remember(tick) { pilotWatchEnabled(context) }
    val last = remember(tick) { store.sp.getString("kap_autodji_last", "") ?: "" }

    Text(
        "АВТО ВЗЛІТ/ПОСАДКА (DJI Pilot 2)",
        color = cs.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = autoDji, onCheckedChange = {
            autoDji = it
            store.putBool("kap_autodji", it)
        })
        Text("Відмічати автоматично за станом дрона", fontSize = 14.sp)
    }
    var templateFab by remember { mutableStateOf(store.sp.getBoolean("kap_tplfab", true)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = templateFab, onCheckedChange = {
            templateFab = it
            store.putBool("kap_tplfab", it)
        })
        Text("Плаваюча кнопка шаблонів під час вильоту", fontSize = 14.sp)
    }
    // «Діагностика DJI»: dump what the accessibility service sees on the DJI Pilot screen (heading test)
    var diagSum by remember { mutableStateOf(store.sp.getString("kap_dji_diag_sum", "") ?: "") }
    TextButton(onClick = {
        store.sp.edit().putLong("kap_dji_diag_until", System.currentTimeMillis() + 60_000L).apply()
        android.widget.Toast.makeText(context, "Відкрий DJI Pilot (екран польоту з компасом) на 10–20 с, потім повернись сюди", android.widget.Toast.LENGTH_LONG).show()
    }) { Text("🔎 Діагностика DJI (60 с)", color = OkGreen) }
    val diagFile = java.io.File(context.filesDir, "dji_diag.txt")
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(1500); diagSum = store.sp.getString("kap_dji_diag_sum", "") ?: "" }
    }
    if (diagSum.isNotEmpty()) Text(diagSum, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (diagFile.exists()) TextButton(onClick = {
        val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
        val out = java.io.File(dir, "dji_diag.txt"); diagFile.copyTo(out, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Діагностика DJI",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }) { Text("Поділитися діагностикою") }
    // «Курс додому»: what the screen reader sees (calibration)
    var hdgInfo by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            val r = com.example.report.rec.ScreenRec
            hdgInfo = if (!r.headingOn) "Курс: вимкнено" else "Курс: " + (r.heading?.let { "%03d°".format(it) } ?: "---") + " · OCR «" + r.lastRaw + "»"
            kotlinx.coroutines.delay(1000)
        }
    }
    Text(hdgInfo, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    var rect by remember { mutableStateOf(store.sp.getString("kap_hdg_rect", "") ?: "") }
    OutlinedTextField(
        value = rect, onValueChange = { rect = it; store.putString("kap_hdg_rect", it.trim()) },
        label = { Text("Область курсу x0,y0,x1,y1 (частки екрана)") },
        placeholder = { Text("0.455,0.70,0.545,0.785") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val crop = java.io.File(context.filesDir, "hdg_crop.png")
    if (crop.exists()) TextButton(onClick = {
        val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
        val out = java.io.File(dir, "hdg_crop.png"); crop.copyTo(out, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Кадр курсу",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }) { Text("Поділитися кадром курсу") }
    if (serviceOn) {
        Text("✓ Служба працює", color = OkGreen, fontSize = 12.sp)
    } else {
        Text(
            "Служба вимкнена: Спеціальні можливості → «Report: авто взліт/посадка» → увімкнути.",
            color = ErrRed,
            fontSize = 12.sp,
        )
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text("Відкрити спеціальні можливості") }
    }
    Text(
        "Взліт — коли Pilot 2 показує політ (ручне керування, маршрут, повернення додому…), посадка — коли знову «Готовність до польоту». Втрата зв'язку посадкою не вважається.",
        Modifier.padding(top = 2.dp),
        color = cs.onSurfaceVariant,
        fontSize = 11.sp,
    )
    if (last.isNotEmpty()) {
        Text(
            "Останній стан DJI: $last",
            Modifier.padding(top = 2.dp),
            color = cs.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = cs.outline)
    Spacer(Modifier.height(12.dp))
}

// ==== Local-network sync =========================================================================

/** "REPORTSYNC:ip:code" (or "ip|code", "ip;code") from the hub QR → (ip, code). */
private fun parseHubQr(raw: String): Pair<String, String>? {
    val s = raw.trim().removePrefix("REPORTSYNC:")
    val parts = s.split(":", "|", ";")
    if (parts.size < 2) return null
    val ip = parts[0].trim()
    val code = parts[1].trim()
    return if (ip.isNotEmpty() && code.isNotEmpty()) ip to code else null
}

/** 560×560 QR bitmap for [text], or null if encoding fails. */
private fun qrBitmap(text: String): Bitmap? = try {
    val size = 560
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bmp
} catch (e: Exception) {
    null
}

@Composable
fun NetworkDialog(vm: ReportViewModel, dismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = { TextButton(onClick = dismiss) { Text("Закрити") } },
        title = { Text("Синхронізація (локальна мережа)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (vm.netStatus.isNotBlank()) {
                    Text(
                        vm.netStatus,
                        Modifier.padding(bottom = 8.dp),
                        color = cs.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
                    val contents = result.contents ?: return@rememberLauncherForActivityResult
                    parseHubQr(contents)?.let { (ip, code) -> vm.connectToHub(ip, code) }
                }
                when (vm.netMode) {
                    NetMode.SOLO -> {
                        Text(
                            "Спільна база на кілька телефонів в одній Wi-Fi. Один — «хаб», решта підключаються до нього.",
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { vm.startHub(null) }) { Text("Стати хабом") }
                        HorizontalDivider(color = cs.outline)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Підключитися до хаба:",
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        DisposableEffect(Unit) {
                            vm.discoverHubs()
                            onDispose {
                                vm.nsd.stopDiscovery()
                                vm.foundHubs.clear()
                            }
                        }
                        var ip by remember { mutableStateOf("") }
                        var code by remember { mutableStateOf("") }
                        Text("🔎 Хаби поблизу:", color = OkGreen, fontSize = 12.sp)
                        if (vm.foundHubs.isEmpty()) {
                            Text(
                                "пошук…",
                                Modifier.padding(start = 4.dp),
                                color = cs.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                        for (found in vm.foundHubs) {
                            TextButton(onClick = { ip = found.ip }, modifier = Modifier.fillMaxWidth()) {
                                Text(found.name + " · " + found.ip, fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            scanLauncher.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("Скануйте QR хаба")
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(false)
                                    .setCaptureActivity(com.example.report.scan.ScanActivity::class.java),
                            )
                        }) { Text("📷 Сканувати QR хаба", color = OkGreen) }
                        Spacer(Modifier.height(6.dp))
                        Text("або вручну:", color = cs.onSurfaceVariant, fontSize = 11.sp)
                        OutlinedTextField(
                            value = ip,
                            onValueChange = { ip = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("IP хаба (напр. 192.168.0.5)") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Код (XXXX-XXXX-XXXX-XXXX)") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            if (ip.isNotBlank() && code.isNotBlank()) vm.connectToHub(ip, code)
                        }) { Text("Підключитися") }
                    }

                    NetMode.HUB -> {
                        Text("Ви — ХАБ.", color = OkGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        val payload = "REPORTSYNC:" + ReportViewModel.hubLocalIp() + ":" + vm.netCode
                        val qr = remember(payload) { qrBitmap(payload) }
                        if (qr != null) {
                            Image(
                                bitmap = qr.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(240.dp),
                                alignment = Alignment.Center,
                                contentScale = ContentScale.Fit,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "IP: " + ReportViewModel.hubLocalIp() + " · Код: " + NetUtil.prettyCode(vm.netCode),
                            fontSize = 13.sp,
                        )
                        Text(
                            "Підключено пристроїв: " + vm.netPeers,
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "На іншому телефоні: Синхронізація → «Сканувати QR хаба».",
                            color = cs.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { vm.stopNet() }) { Text("Зупинити хаб", color = ErrRed) }
                    }

                    NetMode.CLIENT -> {
                        Text(
                            "Підключено як клієнт.",
                            color = OkGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { vm.stopNet() }) { Text("Відключитися", color = ErrRed) }
                    }
                }
            }
        },
    )
}

// ==== Guide ======================================================================================

private val GUIDE = listOf(
    "1 · Налаштування" to "Кнопка ☰ (зліва вгорі) відкриває меню: назва позиції, позивний старшого групи, скидання зміни, тема. Зберігається автоматично.",
    "2 · Фіксація вильотів" to "«Взліт» — почався виліт, таймер рахує час. Поки виліт триває (між «Взльотом» і «Сів»), тапайте по шаблонах — коментарі додаються до цього вильоту з часовою міткою. Так само в проміжку підставляються координати з карти («У звіт»). «Сів» — посадка завершує виліт.",
    "3 · Режим звіту" to "Зараз — поточний виліт. День/Ніч. Доба — від початку зміни. 7 днів — тиждень.",
    "4 · Редактор (олівець)" to "Уже зафіксовані вильоти можна виправити пізніше: у режимі День/Ніч/Доба тапніть олівець — відкриється редактор. Там правте час взльоту й посадки, коментарі, координати, додавайте чи видаляйте вильоти. День/Ніч також має втрати, пропелери, дрони; Доба — події зміни.",
    "5 · Надсилання" to "«Перегляд звіту» — готовий текст (можна правити). «Поділитися» або «Копіювати».",
    "6 · Карта" to "Довге натискання по мапі (пару секунд) — нова точка з координатами MGRS. Тап по точці — меню (назва, колір, «У звіт»). Після «У звіт» оберіть, куди підставити координати: до конкретного шаблону поточного вильоту (зі списку його рядків) або «До вильоту» — до вильоту загалом.",
    "7 · Потреби" to "Кнопка зі списком — блокнот потреб. Кожен Enter — новий пункт, нумерація вмикається кнопкою «1-2-3». Текст зберігається сам; «Поділитися» надсилає список, кошик — очищає.",
)

@Composable
fun GuideDialog(dismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = { TextButton(onClick = dismiss) { Text("Зрозуміло") } },
        title = { Text("Як скласти й надіслати звіт") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((head, body) in GUIDE) {
                    Text(head, color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(body, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Дані зберігаються лише на цьому пристрої. Вильоти — 8 діб.",
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        },
    )
}
