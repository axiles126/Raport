package com.example.report

import com.example.report.core.AutoFlightTracker
import com.example.report.core.AutoFlightTracker.Action
import com.example.report.core.PilotStatus
import com.example.report.core.PilotStatus.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFlightTest {
    private val t0 = 1_790_000_000_000L
    private val tr = AutoFlightTracker()
    private val events = ArrayList<AutoFlightTracker.Event>()
    private var sec = 0

    /** [n] one-second ticks with this status text and altitude. */
    private fun run(n: Int, text: String, alt: Double?) = repeat(n) {
        tr.feed(PilotStatus.classify(text), t0 + sec * 1000L, alt)?.let { events += it }; sec++
    }
    private fun at(s: Int) = t0 + s * 1000L

    @Test fun manualLandingThenSwitchOff() {
        run(5, "Готов к полету (GPS)", 0.0)
        run(2, "Ручное управление", 0.3)           // motors up, lifting   (sec 5..6)
        run(60, "Ручное управление", 40.0)          // flying               (sec 7..66)
        run(3, "Ручное управление", 0.2)           // touched down         (sec 67..69)
        run(20, "Соединение с дроном прервано", null) // drone switched off
        assertEquals(listOf(Action.TAKEOFF, Action.LANDING), events.map { it.action })
        assertEquals(at(5), events[0].at)
        assertEquals(at(67), events[1].at)
    }

    @Test fun autoLandingStatus() {
        run(3, "Готов", 0.0); run(30, "В полете", 50.0); run(10, "Посадка", 5.0); run(3, "Готов к полету", 0.0)
        assertEquals(listOf(Action.TAKEOFF, Action.LANDING), events.map { it.action })
        assertEquals(at(43), events[1].at)
    }

    @Test fun linkLostAtHeightIsNotLanding() {
        run(3, "Готов", 0.0); run(30, "Ручное управление", 120.0); run(120, "Соединение с дроном прервано", null)
        assertEquals(listOf(Action.TAKEOFF), events.map { it.action })
        run(1, "N/A", 0.0) // widget shows 0 while the link is down → still not a landing
        assertEquals(1, events.size)
    }

    @Test fun manualTakeoffPressedOnGround() {
        run(3, "Готов к полету", 0.0)
        tr.setFlying(true)                     // ВЗЛІТ pressed by hand before lift-off
        run(20, "Готов к полету", 0.0)
        assertTrue(events.isEmpty())
        run(5, "Ручное управление", 15.0); run(2, "Готов к полету", 0.0)
        assertEquals(listOf(Action.LANDING), events.map { it.action })
    }

    @Test fun takeoffByAltitudeWithUnknownText() {
        run(3, "Какой-то новый режим", 0.0)
        run(4, "Какой-то новый режим", 3.0)   // H rises at sec 3
        assertEquals(Action.TAKEOFF, events.single().action)
        assertEquals(at(3), events.single().at)
    }

    @Test fun landedAwayFromHomeDoesNotTakeOffAgain() {
        run(3, "Готов", 0.0); run(20, "Ручное управление", 60.0); run(3, "Готов к полету", 20.0)
        run(30, "Готов к полету", 20.0)
        assertEquals(listOf(Action.TAKEOFF, Action.LANDING), events.map { it.action })
    }

    @Test fun parseAlt() {
        assertEquals(12.3, AutoFlightTracker.parseAlt("H 12.3m")!!, 1e-9)
        assertEquals(-0.4, AutoFlightTracker.parseAlt("-0,4 м")!!, 1e-9)
        assertEquals(30.48, AutoFlightTracker.parseAlt("100 ft")!!, 1e-6)
        assertNull(AutoFlightTracker.parseAlt("N/A"))
    }
}
