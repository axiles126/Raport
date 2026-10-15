package com.example.report.core

import com.example.report.core.PilotStatus.Kind

/**
 * Debounces AIR/GROUND status changes: a new state must hold for [STABLE_MS]
 * before it is accepted and a takeoff/landing [Action] is emitted.
 */
class AutoFlightTracker {
    enum class Action { TAKEOFF, LANDING }

    private var state: Kind? = null
    private var cand: Kind? = null
    private var candSince: Long = 0L

    // TODO(decomp): inlined into PilotWatchService.check(); name/signature reconstructed.
    fun feed(kind: Kind, now: Long): Action? {
        if (kind != Kind.AIR && kind != Kind.GROUND) {
            cand = null
            return null
        }
        val prev = state
        if (kind == prev) {
            cand = null
            return null
        }
        if (cand != kind) {
            cand = kind
            candSince = now
            return null
        }
        if (now - candSince < STABLE_MS) return null
        state = kind
        cand = null
        return when {
            kind == Kind.AIR -> Action.TAKEOFF
            prev == Kind.AIR -> Action.LANDING
            else -> null
        }
    }

    companion object {
        const val STABLE_MS = 3000L
    }
}
