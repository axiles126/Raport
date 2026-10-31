package com.example.report

import com.example.report.core.DroneSync
import com.example.report.data.DroneIn
import org.junit.Assert.assertEquals
import org.junit.Test

class DroneSyncTest {
    private val now = 1_790_000_000_000L
    private fun d(t: Long, code: String = "", u: Long = 0) = DroneIn(t = t, code = code, u = u)

    @Test fun lostOpThenStaleSnapshotKeepsNewDrone() {
        // phone added drone #3 while its op was lost; hub snapshot (without #3) must not wipe it
        val hub = listOf(d(1, u = 10), d(2, u = 20))
        val phone = listOf(d(1, u = 10), d(2, u = 20), d(3, u = 30))
        assertEquals(listOf(1L, 2L, 3L), DroneSync.merge(phone, hub, emptyMap()).map { it.t })
        // and the hub picks #3 up from the phone's state
        assertEquals(listOf(1L, 2L, 3L), DroneSync.merge(hub, phone, emptyMap()).map { it.t })
    }

    @Test fun bothAddDifferentDrones() {
        val a = listOf(d(1, u = 1), d(5, u = 5)); val b = listOf(d(1, u = 1), d(7, u = 7))
        assertEquals(listOf(1L, 5L, 7L), DroneSync.merge(a, b, emptyMap()).map { it.t })
    }

    @Test fun newerEditWins() {
        val a = listOf(d(1, "OLD", u = 1)); val b = listOf(d(1, "NEW", u = 2))
        assertEquals("NEW", DroneSync.merge(a, b, emptyMap()).single().code)
        assertEquals("NEW", DroneSync.merge(b, a, emptyMap()).single().code)
    }

    @Test fun deletionTravelsAndDoesNotComeBack() {
        val prev = listOf(d(1, u = 1), d(2, u = 2))
        val (cur, tombs) = DroneSync.stamp(prev, listOf(d(1, u = 1)), emptyMap(), now)
        assertEquals(setOf("2:in"), tombs.keys)
        // a stale device still has #2
        val stale = listOf(d(1, u = 1), d(2, u = 2))
        assertEquals(listOf(1L), DroneSync.merge(cur, stale, tombs).map { it.t })
        assertEquals(listOf(1L), DroneSync.merge(stale, cur, DroneSync.mergeTombs(emptyMap(), tombs, now)).map { it.t })
    }

    @Test fun stampMarksOnlyChanged() {
        val prev = listOf(d(1, "A", u = 5), d(2, "B", u = 5))
        val (cur, _) = DroneSync.stamp(prev, listOf(d(1, "A", u = 5), d(2, "B2", u = 5), d(3, "C")), emptyMap(), now)
        assertEquals(listOf(5L, now, now), cur.map { it.u })
    }
}
