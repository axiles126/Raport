package com.example.report

import com.example.report.data.Flight
import com.example.report.data.Route
import com.example.report.sync.Op
import com.example.report.sync.OpGuard
import com.example.report.sync.SharedState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpGuardTest {
    private val now = 1_790_000_000_000L
    private val f = Flight(now - 3_600_000, "10:01 Загальна розвідка", now - 1_800_000, u = now)

    @Test fun normalPasses() {
        assertTrue(OpGuard.op(Op.UpsertFlight(f, f), now))
        assertTrue(OpGuard.op(Op.Takeoff(0), now))
        assertTrue(OpGuard.op(Op.AddPoint(48.46, 35.04), now))
        assertTrue(OpGuard.op(Op.ResetShift(), now))
        assertTrue(OpGuard.state(SharedState(flights = listOf(f), routes = listOf(Route("r", "R", pts = listOf(48.0 to 35.0)))), now))
    }

    @Test fun garbageRejected() {
        assertFalse(OpGuard.op(Op.SetFlightComment(f.t, "x".repeat(OpGuard.MAX_TEXT + 1)), now))
        assertFalse(OpGuard.op(Op.RemoveTemplate(-1), now))
        assertFalse(OpGuard.op(Op.AddPoint(Double.NaN, 35.0), now))
        assertFalse(OpGuard.op(Op.AddPoint(91.0, 35.0), now))
        assertFalse(OpGuard.op(Op.UpsertFlight(f.copy(t = 5)), now))
        assertFalse(OpGuard.op(Op.SetName("x".repeat(1000)), now))
        assertFalse(OpGuard.state(SharedState(flights = List(OpGuard.MAX_LIST + 1) { f.copy(t = f.t + it) }), now))
    }
}
