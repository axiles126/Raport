package com.example.report

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.report.alarm.ReportAlarmReceiver
import com.example.report.overlay.FlightBridge
import com.example.report.overlay.OverlayService
import com.example.report.ui.LossScreen
import com.example.report.ui.MainScreen
import com.example.report.ui.MapScreen
import com.example.report.ui.NeedsScreen
import com.example.report.ui.ReportPad
import com.example.report.ui.ReportViewModel
import com.example.report.ui.SettingsDialog
import com.example.report.ui.prefixes
import com.example.report.ui.theme.ErrRed
import com.example.report.ui.theme.ReportTheme
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {

    /** (cause, timestamp) of a loss reported from the floating overlay, consumed by the UI. */
    private var pendingLoss by mutableStateOf<Pair<String, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeLoss(intent)
        setContent {
            val vm: ReportViewModel = viewModel()
            val dark = when (vm.themeMode) {
                "dark" -> true
                "light" -> false
                else -> null
            }
            ReportTheme(dark) {
                Surface(Modifier.fillMaxSize()) { App(vm) }
                LaunchedEffect(pendingLoss) {
                    pendingLoss?.let { (cause, at) ->
                        vm.reportLoss(at, cause)
                        pendingLoss = null
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeLoss(intent)
    }

    override fun onResume() {
        super.onResume()
        FlightBridge.uiResumed = true
        FlightBridge.onUiChanged?.invoke()
    }

    override fun onPause() {
        FlightBridge.uiResumed = false
        FlightBridge.onUiChanged?.invoke()
        super.onPause()
    }

    private fun takeLoss(intent: Intent?) {
        val cause = intent?.getStringExtra("loss_cause") ?: return
        pendingLoss = cause to intent.getLongExtra("loss_at", System.currentTimeMillis())
        intent.removeExtra("loss_cause")
    }
}

@Composable
fun App(vm: ReportViewModel) {
    val context = LocalContext.current
    var showMap by remember { mutableStateOf(false) }
    var showNeeds by remember { mutableStateOf(false) }
    var showPad by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    LaunchedEffect(vm.shareTrigger) {
        if (vm.shareTrigger > 0) doShare(context, vm)
    }

    // Direct send to WhatsApp (optionally to a phone via wa.me) and/or Signal.
    LaunchedEffect(vm.directTrigger) {
        if (vm.directTrigger <= 0) return@LaunchedEffect
        val text = if (vm.previewVisible) vm.previewText else vm.buildReport()
        if (vm.autoWhatsApp) {
            val phone = vm.waPhone.filter { it.isDigit() }
            if (openInApp(context, "com.whatsapp", text, phone) || openInApp(context, "com.whatsapp.w4b", text, phone)) {
                vm.setStatus("Відкрито WhatsApp — натисніть «Надіслати»", "ok")
            } else {
                vm.setStatus("WhatsApp не встановлено", "err")
            }
        }
        if (vm.autoSignal) {
            if (openInApp(context, "org.thoughtcrime.securesms", text, null)) {
                vm.setStatus("Відкрито Signal — оберіть отримувача", "ok")
            } else {
                vm.setStatus("Signal не встановлено", "err")
            }
        }
    }

    // "Test" button of a scheduled send: open the messenger now with that schedule's report.
    LaunchedEffect(vm.scheduleTestTrigger) {
        if (vm.scheduleTestTrigger <= 0) return@LaunchedEffect
        val s = vm.scheduleTest ?: return@LaunchedEffect
        val text = runCatching { ReportAlarmReceiver.buildText(context, s.mode) }.getOrDefault("")
        if (text.isEmpty()) return@LaunchedEffect
        val signal = s.app == "signal"
        val pkg = if (signal) "org.thoughtcrime.securesms" else "com.whatsapp"
        val appName = if (signal) "Signal" else "WhatsApp"
        if (openInApp(context, pkg, text, null) || (!signal && openInApp(context, "com.whatsapp.w4b", text, null))) {
            vm.setStatus("Відкрито $appName — оберіть групу", "ok")
        } else {
            vm.setStatus("$appName не встановлено", "err")
        }
    }

    LaunchedEffect(Unit) {
        if (vm.overlayEnabled && Settings.canDrawOverlays(context)) OverlayService.start(context)
    }

    LaunchedEffect(Unit) { vm.restoreNet() }

    // Alarm ringing: tone + vibration every 2 s, at most 75 times, until stopped.
    LaunchedEffect(vm.ringing) {
        if (!vm.ringing) return@LaunchedEffect
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        var n = 0
        while (vm.ringing && n < 75) {
            tone?.startTone(53, 600) // TODO(decomp): raw tone id 53 in the APK
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
            n++
            delay(2000)
        }
        tone?.release()
    }

    Box(Modifier.fillMaxSize()) {
        // TODO(decomp): screen parameter names unknown — arguments are passed positionally in APK order.
        MainScreen(
            vm,
            { doShare(context, vm) }, // share report
            { doCopy(context, vm) }, // copy report
            { showMap = true },
            { showNeeds = true },
            { showPad = true },
            { showSettings = true },
            { shareApk(context, vm) },
        )
        if (showNeeds) {
            NeedsScreen(vm, { shareNeeds(context, vm) }, { showNeeds = false })
        }
        if (showPad) {
            ReportPad(vm, { doShare(context, vm) }, { doCopy(context, vm) }, { showPad = false })
        }
        if (vm.lossDraft != null) {
            LossScreen(
                vm,
                { shareLoss(context, vm) },
                { copyLoss(context, vm) },
                { showMap = true },
                { vm.dismissLoss() },
            )
        }
        if (showMap) {
            MapScreen(vm) { showMap = false }
        }
        if (vm.ringing) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = ErrRed,
            ) {
                Row(
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(vm.ringText, Modifier.weight(1f), color = Color.White, fontSize = 14.sp)
                    TextButton(onClick = { vm.stopRing() }) { Text("Стоп", color = Color.White) }
                }
            }
        }
    }
    if (showSettings) {
        SettingsDialog(vm) { showSettings = false }
    }
}

private fun reportText(vm: ReportViewModel): String = if (vm.previewVisible) vm.previewText else vm.buildReport()

private fun doCopy(context: Context, vm: ReportViewModel) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Звіт", reportText(vm)))
    vm.setStatus("✓ Скопійовано", "ok")
}

private fun doShare(context: Context, vm: ReportViewModel) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, reportText(vm))
        putExtra(Intent.EXTRA_SUBJECT, vm.name.ifBlank { "Звіт" })
    }
    context.startActivity(Intent.createChooser(intent, "Поділитися звітом"))
}

/** Opens [pkg] with [text]; with a [phone], WhatsApp is opened on that chat via wa.me. */
private fun openInApp(context: Context, pkg: String, text: String, phone: String?): Boolean = try {
    val intent = if (!phone.isNullOrEmpty()) {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + phone + "?text=" + Uri.encode(text)))
    } else {
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }
    intent.setPackage(pkg)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
} catch (e: Exception) {
    false
}

/** Shares this app's own APK (for side-loading onto other devices). */
private fun shareApk(context: Context, vm: ReportViewModel) {
    try {
        val src = File(context.applicationInfo.sourceDir)
        val dst = File(context.cacheDir, "Report.apk")
        src.copyTo(dst, overwrite = true)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", dst)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Report.apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Передати застосунок")
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    } catch (e: Exception) {
        vm.setStatus("Не вдалося передати застосунок: " + e.message, "err")
    }
}

private fun shareNeeds(context: Context, vm: ReportViewModel) {
    val text = if (vm.needsNumbered) {
        val lines = vm.needsText.split('\n')
        // TODO(decomp): `prefixes` lives with NumberedParagraphs (merged into kotlin.math.MathKt).
        lines.zip(prefixes(vm.needsText, false)) { line, prefix -> prefix + line }
            .joinToString("\n")
            .trim('\n', ' ')
    } else {
        vm.needsText.trim()
    }
    if (text.isBlank()) {
        vm.setStatus("Потреби порожні", "err")
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, if (vm.name.isBlank()) "Потреби" else "Потреби — " + vm.name)
    }
    context.startActivity(Intent.createChooser(intent, "Поділитися потребами"))
}

private fun shareLoss(context: Context, vm: ReportViewModel) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, vm.lossMessage())
        putExtra(Intent.EXTRA_SUBJECT, "Втрата борту")
    }
    context.startActivity(Intent.createChooser(intent, "Повідомити про втрату"))
}

private fun copyLoss(context: Context, vm: ReportViewModel) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Втрата борту", vm.lossMessage()))
    vm.setStatus("✓ Скопійовано", "ok")
}
