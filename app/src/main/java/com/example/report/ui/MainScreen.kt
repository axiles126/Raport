package com.example.report.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.report.core.Loss
import com.example.report.overlay.OverlayService
import com.example.report.ui.theme.ErrRed
import com.example.report.ui.theme.OkGreen
import kotlinx.coroutines.launch

private val MODES = listOf(
    "current" to "Зараз",
    "day" to "День",
    "night" to "Ніч",
    "shift" to "Доба",
    "week" to "7 днів",
)

private val THEMES = listOf(
    "system" to "Система",
    "light" to "Світла",
    "dark" to "Темна",
)

/**
 * Main screen: flight timer with takeoff/landing, templates, report mode tabs, report preview
 * and the editor; the "☰" drawer holds position data and screen settings.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    vm: ReportViewModel,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenNeeds: () -> Unit,
    onOpenPad: () -> Unit,
    onOpenSettings: () -> Unit,
    onShareApk: () -> Unit,
    onOpenStrike: () -> Unit = {},
    onOpenArchive: () -> Unit = {},
) {
    var showTemplates by remember { mutableStateOf(false) }
    var showDrones by remember { mutableStateOf(false) }
    var droneDraft by remember { mutableStateOf<DroneDraft?>(null) }
    var showDroneMenu by remember { mutableStateOf(false) }
    var confirmCancelFlight by remember { mutableStateOf(false) }
    var showLandMenu by remember { mutableStateOf(false) }
    var droneScanRequest by remember { mutableStateOf<String?>(null) }
    var deleteTemplates by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var addingTemplate by remember { mutableStateOf(false) }
    var showLoss by remember { mutableStateOf(false) }
    var showNetwork by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val cs = MaterialTheme.colorScheme
    vm.dataRev // read so the screen recomposes on every data change

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val clockSize = 44.sp
    // Landscape: shrink the layout but keep text readable.
    val baseDensity = LocalDensity.current
    val density = if (landscape) {
        Density(baseDensity.density * 0.6f, baseDensity.fontScale * 1.5f)
    } else {
        baseDensity
    }

    ModalNavigationDrawer(
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(vm, cs, onShareApk) { showNetwork = true }
            }
        },
        drawerState = drawerState,
    ) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalDensity provides density) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(scroll)
                            .widthIn(max = if (landscape) Dp.Infinity else 640.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        // ---- title (position · leader), opens the drawer ----
                        val title = vm.name.ifBlank { "Назва" }
                        Row(
                            Modifier.fillMaxWidth().height(48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.fillMaxWidth(0.25f))
                            Text(
                                title + if (vm.leader.isNotBlank()) " · " + vm.leader else "",
                                Modifier.clickableNoRipple { scope.launch { drawerState.open() } },
                                color = cs.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        // ---- timer ----
                        val since = System.currentTimeMillis() - vm.DAY
                        val last24h = vm.flights.count { it.t >= since }
                        Text(
                            "Вилетів за 24 год: $last24h",
                            Modifier.fillMaxWidth(),
                            color = cs.onSurface,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            vm.clock,
                            Modifier.fillMaxWidth(),
                            color = if (vm.clockLive) OkGreen else cs.onSurface,
                            fontSize = clockSize,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            vm.flightInfo,
                            Modifier.fillMaxWidth(),
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))

                        // ---- takeoff / landing ----
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            // tap = takeoff/landing; long press while flying = «Посадка?» menu (втрата / не зберігати)
                            Box(
                                Modifier
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                                    .combinedClickable(
                                        onClick = { vm.toggleFlight() },
                                        onLongClick = { if (vm.flying) showLandMenu = true },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    if (vm.flying) "СІВ" else "ВЗЛІТ ПІШОВ",
                                    color = if (vm.flying) ErrRed else OkGreen,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                )
                            }
                        }
                        // ---- «Заїхав / Переданий дрон»: QR -> entry dialog -> separate lists ----
                        var scanKind by remember { mutableStateOf(DRONE_IN) }
                        val droneScan = rememberLauncherForActivityResult(ScanContract()) { r ->
                            r.contents?.let { droneDraft = DroneDraft(-1, scanKind, vm.droneModel, "", it.trim()) }
                        }
                        androidx.compose.runtime.LaunchedEffect(droneScanRequest) {
                            droneScanRequest?.let { k -> scanKind = k; droneScanRequest = null; droneScan.launch(droneScanOptions()) }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { showDroneMenu = true }) {
                                Icon(Icons.Filled.QrCodeScanner, null, tint = cs.primary)
                                Spacer(Modifier.width(6.dp))
                                Text("ДРОН", color = cs.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            }
                        }
                        Spacer(Modifier.height(4.dp))

                        // ---- templates toggle + copy ----
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { showTemplates = !showTemplates }) {
                                val tc = if (showTemplates) OkGreen else cs.onSurfaceVariant
                                Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null, tint = tc)
                                Spacer(Modifier.width(6.dp))
                                Text("ШАБЛОНИ", color = tc, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = onCopy) {
                                Icon(Icons.Filled.ContentCopy, "Скопіювати", tint = cs.onSurfaceVariant)
                            }
                        }
                        if (showTemplates) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                vm.templates.forEachIndexed { i, tpl ->
                                    TextButton(onClick = {
                                        if (deleteTemplates) vm.removeTemplate(i) else vm.insertTemplate(tpl)
                                    }) {
                                        Text(
                                            (if (deleteTemplates) "✕ " else "") + tpl,
                                            color = if (deleteTemplates) ErrRed else cs.onSurface,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                TextButton(onClick = { addingTemplate = !addingTemplate }) {
                                    Text("+", color = cs.onSurfaceVariant, fontSize = 20.sp)
                                }
                                TextButton(onClick = { deleteTemplates = !deleteTemplates }) {
                                    Text(if (deleteTemplates) "готово" else "✎", color = cs.onSurfaceVariant)
                                }
                            }
                            if (addingTemplate) {
                                var newTemplate by remember { mutableStateOf("") }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = newTemplate,
                                        onValueChange = { newTemplate = it },
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text("Новий шаблон…") },
                                        singleLine = true,
                                    )
                                    TextButton(onClick = {
                                        val added = vm.addTemplates(newTemplate)
                                        newTemplate = ""
                                        addingTemplate = false
                                        if (added > 0) vm.setStatus("✓ Додано: $added", "ok")
                                    }) { Text("Додати") }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Divider(color = cs.outline)
                        Spacer(Modifier.height(10.dp))

                        // ---- report mode tabs ----
                        Text(
                            "РЕЖИМ ЗВІТУ",
                            color = cs.onSurface,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for ((key, label) in MODES) {
                                TextButton(
                                    onClick = { vm.changeMode(key) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(2.dp),
                                ) {
                                    val sel = vm.mode == key
                                    Text(
                                        label,
                                        color = if (sel) cs.onSurface else cs.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))

                        // ---- action icons ----
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (landscape) Arrangement.SpaceEvenly else Arrangement.Center,
                        ) {
                            IconButton(onClick = onShare) {
                                Icon(Icons.Filled.Share, "Поділитися", tint = cs.onSurface)
                            }
                            IconButton(onClick = { vm.togglePreview() }) {
                                Icon(
                                    Icons.Filled.Description,
                                    "Перегляд звіту",
                                    tint = if (vm.previewVisible) OkGreen else cs.onSurface,
                                )
                            }
                            IconButton(onClick = { showEditor = !showEditor }) {
                                Icon(Icons.Filled.Edit, "Редактор", tint = if (showEditor) OkGreen else cs.onSurface)
                            }
                            IconButton(onClick = onOpenMap) {
                                Icon(Icons.Filled.Place, "Карта", tint = cs.onSurface)
                            }
                            IconButton(onClick = onOpenNeeds) {
                                Icon(Icons.Filled.Checklist, "Потреби", tint = cs.onSurface)
                            }
                            IconButton(onClick = onOpenStrike) {
                                Icon(Icons.Filled.GpsFixed, "Ураження", tint = ErrRed)
                            }
                            IconButton(onClick = onOpenArchive) {
                                Icon(Icons.Filled.History, "Архів звітів", tint = cs.onSurface)
                            }
                        }

                        // ---- «Попередня доба»: first hours of a new shift → yesterday's report in one tap ----
                        if (vm.mode == "shift") {
                            vm.dataRev
                            vm.prevShiftDay()?.let { pd ->
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { vm.archiveInit = "shift" to pd; onOpenArchive() },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, OkGreen),
                                ) {
                                    Text("◀ ПОПЕРЕДНЯ ДОБА · " + com.example.report.core.TimeFmt.fmtD(pd), color = OkGreen, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // ---- report preview (tap opens the full-screen pad) ----
                        if (vm.previewVisible) {
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth().height(220.dp)) {
                                OutlinedTextField(
                                    value = vm.previewText,
                                    onValueChange = {},
                                    modifier = Modifier.fillMaxSize(),
                                    readOnly = true,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                )
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .clickable(onClickLabel = "Відкрити звіт", onClick = onOpenPad),
                                )
                            }
                        }

                        if (showEditor) {
                            Spacer(Modifier.height(8.dp))
                            EditorSection(vm, onOpenMap)
                        }

                        if (vm.status.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                vm.status,
                                Modifier.fillMaxWidth(),
                                color = when (vm.statusCls) {
                                    "ok" -> OkGreen
                                    "err" -> ErrRed
                                    else -> cs.onSurfaceVariant
                                },
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // ---- top bar: ☰ on the left, guide + settings on the right ----
            IconButton(
                onClick = { scope.launch { drawerState.open() } },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(4.dp),
            ) {
                Icon(Icons.Filled.Menu, "Меню", tint = cs.onSurface)
            }
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(4.dp),
            ) {
                IconButton(onClick = { showGuide = true }) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, "Інструкція", tint = cs.onSurface)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, "Налаштування", tint = cs.onSurface)
                }
            }
        }
    }

    if (showGuide) {
        GuideDialog { showGuide = false }
    }

    if (showLoss) {
        AlertDialog(
            onDismissRequest = { showLoss = false },
            confirmButton = {
                TextButton(onClick = { showLoss = false }) { Text("Скасувати") }
            },
            title = { Text("Втрата борту — причина?") },
            text = {
                Column {
                    for (cause in Loss.CAUSES) {
                        TextButton(
                            onClick = {
                                showLoss = false
                                vm.reportLoss(System.currentTimeMillis(), cause)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(Loss.label(cause), color = ErrRed, fontSize = 16.sp)
                        }
                    }
                    TextButton(
                        onClick = { showLoss = false; vm.reportLoss(System.currentTimeMillis(), Loss.CUSTOM) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Своя причина…", color = ErrRed, fontSize = 16.sp) }
                }
            },
        )
    }

    if (showNetwork) {
        NetworkDialog(vm) { showNetwork = false }
    }
    if (showDrones) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showDrones = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) { DroneInScreen(vm) { showDrones = false } }
    }
    droneDraft?.let { DroneEntryDialog(vm, it) { droneDraft = null } }
    if (showLandMenu) {
        AlertDialog(
            onDismissRequest = { showLandMenu = false },
            title = { Text("Посадка?") },
            text = {
                Column {
                    TextButton(onClick = { showLandMenu = false; if (vm.flying) vm.toggleFlight() }, modifier = Modifier.fillMaxWidth()) {
                        Text("СІВ", color = ErrRed, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { showLandMenu = false; showLoss = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("ВТРАТА БОРТУ", color = ErrRed, fontSize = 16.sp)
                    }
                    TextButton(onClick = { showLandMenu = false; confirmCancelFlight = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("НЕ ЗБЕРІГАТИ ВИЛІТ", color = cs.onSurface, fontSize = 16.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLandMenu = false }) { Text("НІ") } },
        )
    }

    if (confirmCancelFlight) {
        AlertDialog(
            onDismissRequest = { confirmCancelFlight = false },
            title = { Text("Не зберігати цей виліт?") },
            text = { Text("Таймер зупиниться, виліт буде видалено і не потрапить у жоден звіт.") },
            confirmButton = {
                TextButton(onClick = { vm.cancelActiveFlight(); confirmCancelFlight = false }) { Text("Не зберігати", color = ErrRed) }
            },
            dismissButton = { TextButton(onClick = { confirmCancelFlight = false }) { Text("Скасувати") } },
        )
    }
    if (showDroneMenu) {
        AlertDialog(
            onDismissRequest = { showDroneMenu = false },
            title = { Text("Дрон") },
            text = {
                Column {
                    TextButton(onClick = { showDroneMenu = false; droneScanRequest = DRONE_IN }, Modifier.fillMaxWidth()) {
                        Text("Заїхав дрон · сканувати QR", color = OkGreen, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { showDroneMenu = false; droneScanRequest = DRONE_OUT }, Modifier.fillMaxWidth()) {
                        Text("Переданий дрон · сканувати QR", color = cs.primary, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { showDroneMenu = false; showDrones = true }, Modifier.fillMaxWidth()) {
                        Text("Списки / вручну / поділитися")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDroneMenu = false }) { Text("Закрити") } },
        )
    }
}

/** Contents of the "☰" drawer: position, screen settings, theme, sync and APK sharing. */
@Composable
private fun DrawerContent(
    vm: ReportViewModel,
    cs: ColorScheme,
    onShareApk: () -> Unit,
    onOpenNetwork: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .padding(20.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("ПОЗИЦІЯ", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = vm.name,
            onValueChange = vm::changeName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Назва позиції") },
            singleLine = true,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = vm.leader,
            onValueChange = vm::changeLeader,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Позивний старшого групи") },
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        ResetMenuItem { vm.resetShift() }
        Spacer(Modifier.height(16.dp))
        Divider(color = cs.outline)
        Spacer(Modifier.height(16.dp))

        Text(
            "НАЛАШТУВАННЯ ЕКРАНА",
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(12.dp))
        CheckRow("Показувати час у польоті («Зараз»)", vm.showAir) { vm.changeShowAir(it) }
        Spacer(Modifier.height(4.dp))
        CheckRow("Великий редактор звіту", vm.bigEdit) { vm.changeBigEdit(it) }
        Spacer(Modifier.height(4.dp))
        CheckRow("Показувати кількість (×N) у «Добі»", vm.showCountShift) { vm.changeShowCountShift(it) }
        Spacer(Modifier.height(4.dp))
        CheckRow("Детальна «Доба»: кожна відмітка — окремий проміжок", vm.shiftDetailed) {
            vm.changeShiftDetailed(it)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().clickableNoRipple { toggleOverlay(context, vm, !vm.overlayEnabled) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = vm.overlayEnabled, onCheckedChange = { toggleOverlay(context, vm, it) })
            Column {
                Text("Плаваюча кнопка «Взліт/Сів»", fontSize = 13.sp)
                Text("поверх усіх застосунків, працює у фоні", color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().clickableNoRipple { toggleRecButton(context, vm, !vm.recButtonEnabled) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = vm.recButtonEnabled, onCheckedChange = { toggleRecButton(context, vm, it) })
            Column {
                Text("Плаваюча кнопка «Запис екрана» ●", fontSize = 13.sp)
                Text("тап — старт/стоп, без звуку · Відео → Movies/Report", color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().clickableNoRipple { toggleHdgPlate(context, vm, !vm.hdgEnabled) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = vm.hdgEnabled, onCheckedChange = { toggleHdgPlate(context, vm, it) })
            Column {
                Text("Плашка «Курс додому» 🧭", fontSize = 13.sp)
                Text("читає курс з компаса DJI · тап — увімк./📌 курс від дому · довгий тап — скинути/вимкнути",
                    color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(16.dp))

        Text("ТЕМА", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((key, label) in THEMES) {
                TextButton(
                    onClick = { vm.changeThemeMode(key) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(2.dp),
                ) {
                    val sel = vm.themeMode == key
                    Text(
                        label,
                        color = if (sel) cs.onSurface else cs.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.Normal,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Divider(color = cs.outline)
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth().clickableNoRipple(onOpenNetwork),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Wifi, "Мережа", tint = if (vm.netMode != NetMode.SOLO) OkGreen else cs.onSurface)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Синхронізація", fontSize = 14.sp)
                Text(
                    when (vm.netMode) {
                        NetMode.HUB -> "Хаб · " + vm.netPeers + " підкл."
                        NetMode.CLIENT -> "Клієнт · " + vm.netStatus
                        else -> "Вимкнено"
                    },
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clickableNoRipple { onShareApk() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Share, "Передати", tint = cs.onSurface)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Передати застосунок", fontSize = 14.sp)
                Text("Швидка передача APK на інший телефон", color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

/** Checkbox + label row; the whole row toggles. */
// TODO(decomp): inlined four times in the APK; extracted here for readability.
@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickableNoRipple { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, fontSize = 13.sp)
    }
}

/**
 * Enables/disables the floating takeoff/landing button. Asks for the overlay permission first.
 * TODO(decomp): a local fun inside MainScreen's drawer content in the APK.
 */
private fun toggleOverlay(context: Context, vm: ReportViewModel, on: Boolean) {
    if (on && !Settings.canDrawOverlays(context)) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        vm.setStatus("Дозвольте «поверх інших застосунків», потім увімкніть ще раз", "dim")
        return
    }
    vm.overlayEnabled = on
    vm.store.putBool("kap_overlay", on)
    syncOverlayService(vm)
}

/** Floating «Запис екрана» button (needs «поверх інших застосунків» like the takeoff button). */
private fun toggleRecButton(context: Context, vm: ReportViewModel, on: Boolean) {
    if (on && !Settings.canDrawOverlays(context)) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        vm.setStatus("Дозвольте «поверх інших застосунків», потім увімкніть ще раз", "dim")
        return
    }
    vm.recButtonEnabled = on
    vm.store.putBool("kap_rec_btn", on)
    syncOverlayService(vm)
}

/** «Курс додому» plate (reads the DJI compass heading from the screen). */
private fun toggleHdgPlate(context: Context, vm: ReportViewModel, on: Boolean) {
    if (on && !Settings.canDrawOverlays(context)) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        vm.setStatus("Дозвольте «поверх інших застосунків», потім увімкніть ще раз", "dim")
        return
    }
    vm.hdgEnabled = on
    vm.store.putBool("kap_hdg_btn", on)
    syncOverlayService(vm)
}

/** The overlay service hosts both buttons; it removes what's off and stops when nothing is on. */
private fun syncOverlayService(vm: ReportViewModel) {
    val app = vm.getApplication<android.app.Application>()
    if (vm.overlayEnabled || vm.recButtonEnabled || vm.hdgEnabled) OverlayService.start(app)
    else app.stopService(Intent(app, OverlayService::class.java))
}
