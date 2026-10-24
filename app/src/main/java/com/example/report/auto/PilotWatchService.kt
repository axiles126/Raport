package com.example.report.auto

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.report.core.AutoFlightTracker
import com.example.report.core.PilotStatus
import com.example.report.core.TimeFmt
import com.example.report.data.Store
import com.example.report.overlay.FlightBridge
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Accessibility service that polls DJI Pilot 2 (com.dji.industry.pilot) once a second, reads the
 * text of its `flight_mode_widget`, classifies it as AIR/GROUND/OFF and turns a debounced
 * GROUND→AIR / AIR→GROUND transition into an automatic takeoff / landing mark.
 * It also hosts the floating templates tab ([TemplateFab]) while a flight is active.
 */
class PilotWatchService : AccessibilityService() {

    private lateinit var store: Store
    private var fab: TemplateFab? = null
    private var lastText: String? = null
    internal val handler = Handler(Looper.getMainLooper())
    private val tracker = AutoFlightTracker()

    private val tick = object : Runnable {
        override fun run() {
            try {
                check()
            } catch (_: Throwable) {
            }
            try {
                updateFab()
            } catch (_: Throwable) {
            }
            try {
                diag()
            } catch (_: Throwable) {
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onServiceConnected() {
        store = Store(this)
        fab = TemplateFab(this)
        FlightBridge.onUiChanged = {
            handler.post {
                try {
                    updateFab()
                } catch (_: Throwable) {
                }
            }
        }
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        FlightBridge.onUiChanged = null
        fab?.update(false)
        super.onDestroy()
    }

    /** Template tab is shown only while flying and the app UI is not in the foreground. */
    internal fun updateFab() {
        val show = store.sp.getBoolean("kap_tplfab", true) && !FlightBridge.uiResumed && FlightBridge.flying(this)
        fab?.update(show)
    }

    private fun check() {
        if (!store.sp.getBoolean("kap_autodji", true)) return

        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            runCatching { windows }.getOrNull()?.forEach { w -> w.root?.let { add(it) } }
        }
        val text = roots.firstNotNullOfOrNull { root ->
            if (root.packageName?.toString() != DJI_PKG) return@firstNotNullOfOrNull null
            root.findAccessibilityNodeInfosByViewId("$DJI_PKG:id/flight_mode_widget")
                .firstOrNull()
                ?.let { nodeText(it) }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } ?: return

        val now = System.currentTimeMillis()
        val kind = PilotStatus.classify(text)
        if (text != lastText) {
            lastText = text
            remember(now, text, kind)
        }

        // TODO(decomp): AutoFlightTracker.feed() / PilotStatus.classify() are inlined here in the APK.
        when (tracker.feed(kind, now)) {
            AutoFlightTracker.Action.TAKEOFF -> {
                if (FlightBridge.flying(this)) return
                FlightBridge.takeoff?.invoke() ?: FlightBridge.storeToggle(this)
                FlightBridge.onStateChanged?.invoke()
                signal(now, "Авто: Взліт " + TimeFmt.hm(now), "ВЗЛІТ")
            }
            AutoFlightTracker.Action.LANDING -> {
                if (FlightBridge.flying(this)) {
                    FlightBridge.landing?.invoke() ?: FlightBridge.storeToggle(this)
                    FlightBridge.onStateChanged?.invoke()
                    signal(now, "Авто: Сів " + TimeFmt.hm(now), "СІВ")
                }
            }
            null -> Unit
        }
    }

    // ---- «Діагностика DJI»: what the service can read on the DJI Pilot 2 screen (for the heading widget) ----
    private var diagN = 0

    /** While «kap_dji_diag_until» is in the future: every 2 s dump all DJI Pilot nodes to files/dji_diag.txt. */
    private fun diag() {
        val until = store.sp.getLong("kap_dji_diag_until", 0L)
        val now = System.currentTimeMillis()
        if (now > until) { diagN = 0; return }
        if (diagN++ % 2 != 0) return
        val lines = ArrayList<String>()
        val nums = LinkedHashSet<String>()
        val r = android.graphics.Rect()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 50 || lines.size > 4000) return
            n.getBoundsInScreen(r)
            val t = n.text?.toString().orEmpty()
            val cd = n.contentDescription?.toString().orEmpty()
            val id = n.viewIdResourceName.orEmpty().substringAfter(":id/")
            if (t.isNotBlank() || cd.isNotBlank() || id.isNotBlank()) {
                lines += "  ".repeat(minOf(d, 12)) + (n.className?.toString()?.substringAfterLast('.') ?: "?") +
                    (if (id.isNotEmpty()) " #$id" else "") + " [${r.left},${r.top},${r.right},${r.bottom}]" +
                    (if (t.isNotBlank()) " t=«$t»" else "") + (if (cd.isNotBlank()) " d=«$cd»" else "")
                for (s in listOf(t, cd)) if (Regex("^\\s*\\d{1,3}(\\.\\d)?\\s*°?\\s*$").matches(s)) nums += "$s @#$id [${r.left},${r.top}]"
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        val roots = buildList {
            runCatching { windows }.getOrNull()?.forEach { w -> w.root?.let { add(it) } }
            rootInActiveWindow?.let { add(it) }
        }.filter { it.packageName?.toString() == DJI_PKG }.distinct()
        roots.forEach { walk(it, 0) }
        val head = "Діагностика DJI · " + java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US).format(java.util.Date(now)) +
            " · вікон DJI: ${roots.size} · елементів: ${lines.size}\nЧисла 0–359 (кандидати на курс):\n" +
            (if (nums.isEmpty()) "  — немає\n" else nums.joinToString("\n", postfix = "\n") { "  $it" }) + "\n"
        if (roots.isEmpty() && java.io.File(filesDir, "dji_diag.txt").exists()) return // keep the last good dump
        java.io.File(filesDir, "dji_diag.txt").writeText(head + lines.joinToString("\n"))
        store.putString("kap_dji_diag_sum", head.lineSequence().first() + " · курс? " + (nums.firstOrNull() ?: "—"))
    }

    /** Appends a timestamped line to the rolling (40 entries) auto-DJI debug log. */
    private fun remember(now: Long, text: String, kind: PilotStatus.Kind) {
        val line = TimeFmt.hm(now) + ":" + TimeFmt.pad2(((now / 1000) % 60).toInt()) + " · " + text + " · " + kind
        store.putString("kap_autodji_last", line)
        val ser = ListSerializer(String.serializer())
        val log = store.load<List<String>>("kap_autodji_log", emptyList())
        store.putString("kap_autodji_log", store.json.encodeToString(ser, log.takeLast(39) + line))
    }

    private fun signal(now: Long, toast: String, tag: String) {
        Toast.makeText(this, toast, Toast.LENGTH_LONG).show()
        (getSystemService(VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
        remember(now, "→ $tag", PilotStatus.Kind.UNKNOWN)
    }

    companion object {
        const val DJI_PKG = "com.dji.industry.pilot"

        /** First non-null text in the node subtree (depth-first). */
        private fun nodeText(node: AccessibilityNodeInfo): String? {
            node.text?.let { return it.toString() }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                nodeText(child)?.let { return it }
            }
            return null
        }
    }
}
