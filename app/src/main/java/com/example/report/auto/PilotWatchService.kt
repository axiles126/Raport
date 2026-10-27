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
        }.filter { it.packageName?.toString() == DJI_PKG }
        val text = roots.firstNotNullOfOrNull { root ->
            root.findAccessibilityNodeInfosByViewId("$DJI_PKG:id/flight_mode_widget")
                .firstOrNull()
                ?.let { nodeText(it) }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } ?: return

        val now = System.currentTimeMillis()
        val kind = PilotStatus.classify(text)
        // Altitude is ignored while the link is down (the widget may freeze or show 0).
        val altRaw = if (kind == PilotStatus.Kind.OFF) null else readAlt(roots)
        val alt = AutoFlightTracker.parseAlt(altRaw)
        if (text != lastText) {
            lastText = text
            remember(now, text + (alt?.let { " · H " + String.format(java.util.Locale.US, "%.1f", it) } ?: ""), kind)
            if (altId == null && kind == PilotStatus.Kind.AIR) rememberCandidates(roots)
        }

        tracker.setFlying(FlightBridge.flying(this))
        val ev = tracker.feed(kind, now, alt) ?: return
        when (ev.action) {
            AutoFlightTracker.Action.TAKEOFF -> {
                if (FlightBridge.flying(this)) return
                FlightBridge.takeoffAt?.invoke(ev.at) ?: FlightBridge.storeToggle(this, ev.at)
                FlightBridge.onStateChanged?.invoke()
                signal(now, "Авто: Взліт " + TimeFmt.hm(ev.at), "ВЗЛІТ " + TimeFmt.hm(ev.at) + " (" + ev.why + ")")
            }
            AutoFlightTracker.Action.LANDING -> {
                if (!FlightBridge.flying(this)) return
                FlightBridge.landingAt?.invoke(ev.at) ?: FlightBridge.storeToggle(this, ev.at)
                FlightBridge.onStateChanged?.invoke()
                signal(now, "Авто: Сів " + TimeFmt.hm(ev.at), "СІВ " + TimeFmt.hm(ev.at) + " (" + ev.why + ")")
            }
        }
    }

    /** View id of the altitude (H) widget once found; tried first, then a search by id keywords. */
    private var altId: String? = null

    private fun readAlt(roots: List<AccessibilityNodeInfo>): String? {
        altId?.let { id ->
            roots.firstNotNullOfOrNull { r -> r.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { nodeText(it) } }
                ?.let { return it }
        }
        for (r in roots) {
            val hit = findNode(r) { n ->
                val id = n.viewIdResourceName?.lowercase() ?: return@findNode false
                ALT_KEYS.any { id.contains(it) } && NOT_ALT.none { id.contains(it) } &&
                    AutoFlightTracker.parseAlt(n.text?.toString()) != null
            } ?: continue
            altId = hit.viewIdResourceName
            store.putString("kap_autodji_altid", altId ?: "")
            return hit.text?.toString()
        }
        return null
    }

    /** Diagnostic: once, log the widgets that look like altitude / distance / speed (id = text). */
    private var candLogged = false
    private fun rememberCandidates(roots: List<AccessibilityNodeInfo>) {
        if (candLogged) return
        val out = ArrayList<String>()
        for (r in roots) walk(r) { n ->
            val id = n.viewIdResourceName?.substringAfter(":id/") ?: return@walk
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && t.length < 24 && (DIAG_KEYS.any { id.lowercase().contains(it) } || Regex("\\d\\s*(m|м)\\b").containsMatchIn(t)))
                if (out.size < 40 && "$id=$t" !in out) out += "$id=$t"
        }
        if (out.isNotEmpty()) { candLogged = true; remember(System.currentTimeMillis(), "widgets: " + out.joinToString("; "), PilotStatus.Kind.UNKNOWN) }
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int = 0, f: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 40) return
        f(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, f) }
    }

    private fun findNode(n: AccessibilityNodeInfo, depth: Int = 0, p: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (depth > 40) return null
        if (p(n)) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> findNode(c, depth + 1, p)?.let { return it } }
        return null
    }

    /** Appends a timestamped line to the rolling (40 entries) auto-DJI debug log. */
    private fun remember(now: Long, text: String, kind: PilotStatus.Kind) {
        val line = TimeFmt.hm(now) + ":" + TimeFmt.pad2(((now / 1000) % 60).toInt()) + " · " + text + " · " + kind
        store.putString("kap_autodji_last", line)
        val ser = ListSerializer(String.serializer())
        val log = store.load<List<String>>("kap_autodji_log", emptyList())
        store.putString("kap_autodji_log", store.json.encodeToString(ser, log.takeLast(LOG_N - 1) + line))
    }

    private fun signal(now: Long, toast: String, tag: String) {
        Toast.makeText(this, toast, Toast.LENGTH_LONG).show()
        (getSystemService(VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
        remember(now, "→ $tag", PilotStatus.Kind.UNKNOWN)
    }

    companion object {
        const val DJI_PKG = "com.dji.industry.pilot"
        const val LOG_N = 80
        private val ALT_KEYS = listOf("altitude", "height", "_alt", "alt_", "_h_value", "tv_h")
        private val NOT_ALT = listOf("asl", "limit", "max", "rth", "gohome", "setting", "unit")
        private val DIAG_KEYS = listOf("pfd", "alt", "height", "dist", "speed", "_h", "vs", "hs")

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
