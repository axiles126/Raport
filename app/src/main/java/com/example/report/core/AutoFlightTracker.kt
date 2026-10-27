package com.example.report.core

import com.example.report.core.PilotStatus.Kind
import kotlin.math.abs

/**
 * Turns the DJI Pilot 2 status stream (status text kind + altitude H, once a second) into
 * takeoff / landing marks. Each [Event] carries the moment the change actually happened,
 * not the moment it was confirmed.
 *
 * Takeoff: a flying status for [AIR_MS], or H ≥ [TAKEOFF_ALT_M] held [RISE_MS] (time = when H left ~0).
 * Landing is detected by any of:
 *  - a ground status («Готов…») for [GROUND_MS] after flying;
 *  - the link dropping («Соединение прервано») when the last altitude was ≤ [LOW_ALT_M]
 *    (the usual case: manual landing, drone switched off right away);
 *  - an unknown status text for [UNKNOWN_MS] after flying;
 *  - altitude ≤ [LOW_ALT_M] for [LOW_MS] while the status still says «flying».
 * A link loss at height is NOT a landing.
 */
class AutoFlightTracker {
    enum class Action { TAKEOFF, LANDING }
    data class Event(val action: Action, val at: Long, val why: String)

    var flying = false
        private set
    private var cand: Kind? = null
    private var candSince = 0L
    private var lastAlt: Double? = null
    private var lastAltAt = 0L
    private var lowSince: Long? = null
    /** Not flying: first moment H went above [LOW_ALT_M] (reset when it drops back). */
    private var riseSince: Long? = null
    /** Flying: seen a flying status / seen H above ~0. Landing rules need them, so a «ВЗЛІТ»
     *  pressed by hand while the drone still stands («Готов») is not closed again at once. */
    private var seenAir = false
    private var seenHigh = false
    /** Not flying: H was seen ≈0 (an H-based takeoff needs a rise FROM the ground, so a drone
     *  that landed away from home with H≠0 does not "take off" again). */
    private var groundLow = false

    /** Keep in sync with the app's real state (manual buttons). */
    fun setFlying(v: Boolean) {
        if (v != flying) { flying = v; cand = null; lowSince = null; riseSince = null; seenAir = false; seenHigh = false; groundLow = false }
    }

    fun feed(kind: Kind, now: Long, alt0: Double? = null): Event? {
        val alt = if (kind == Kind.OFF) null else alt0 // with the link down the widget may freeze / show 0
        if (alt != null) { lastAlt = alt; lastAltAt = now }
        val low = alt != null && abs(alt) <= LOW_ALT_M
        if (cand != kind) { cand = kind; candSince = now }
        val held = now - candSince

        if (!flying) {
            lowSince = null
            if (kind == Kind.OFF) { riseSince = null; return null }
            if (low) groundLow = true
            if (alt != null) riseSince = if (abs(alt) > LOW_ALT_M && groundLow) (riseSince ?: now) else null
            val rs = riseSince
            if (rs != null && alt != null && alt >= TAKEOFF_ALT_M && now - rs >= RISE_MS) {
                flying = true; riseSince = null; groundLow = false; seenAir = kind == Kind.AIR; seenHigh = true
                return Event(Action.TAKEOFF, minOf(rs, if (kind == Kind.AIR) candSince else rs), "H ${"%.0f".format(java.util.Locale.US, alt)} m")
            }
            if (kind == Kind.AIR && held >= AIR_MS) {
                flying = true; groundLow = false; seenAir = true; seenHigh = riseSince != null
                return Event(Action.TAKEOFF, minOf(candSince, riseSince ?: candSince), "status").also { riseSince = null }
            }
            return null
        }
        // flying
        if (kind == Kind.AIR) seenAir = true
        if (alt != null && abs(alt) > LOW_ALT_M) seenHigh = true
        if (alt != null) lowSince = if (low && seenHigh) (lowSince ?: now) else null
        if (!seenAir && !seenHigh) return null
        val ev = when (kind) {
            Kind.GROUND -> if (held >= GROUND_MS) Event(Action.LANDING, candSince, "status") else null
            Kind.OFF -> {
                val a = lastAlt
                if (a != null && abs(a) <= LOW_ALT_M && candSince - lastAltAt <= ALT_FRESH_MS) Event(Action.LANDING, lowSince ?: lastAltAt, "link lost at H≈0")
                else null
            }
            Kind.UNKNOWN -> if (held >= UNKNOWN_MS) Event(Action.LANDING, candSince, "unknown status") else null
            Kind.AIR -> lowSince?.let { if (now - it >= LOW_MS) Event(Action.LANDING, it, "H≈0") else null }
        }
        if (ev != null) { flying = false; lowSince = null; seenAir = false; seenHigh = false; groundLow = false; riseSince = null }
        return ev
    }

    companion object {
        const val AIR_MS = 3_000L
        const val GROUND_MS = 1_000L
        const val UNKNOWN_MS = 30_000L
        const val LOW_MS = 15_000L
        const val LOW_ALT_M = 1.0
        const val TAKEOFF_ALT_M = 2.0
        const val RISE_MS = 2_000L
        const val ALT_FRESH_MS = 15_000L

        /** "H 12.3m", "12,3 м", "-0.4 m" … → metres; ft → m. */
        fun parseAlt(raw: String?): Double? {
            val t = raw?.trim()?.replace(',', '.') ?: return null
            val m = Regex("(-?\\d+(?:\\.\\d+)?)\\s*(m|м|ft)?\\b", RegexOption.IGNORE_CASE).find(t) ?: return null
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            return if (m.groupValues[2].equals("ft", true)) v * 0.3048 else v
        }
    }
}
