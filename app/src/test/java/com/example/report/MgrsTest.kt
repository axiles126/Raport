package com.example.report

import com.example.report.core.Mgrs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MgrsTest {
    private val pts = listOf(
        48.4647 to 35.0462, 50.4501 to 30.5234, 49.9935 to 36.2304, 47.0971 to 37.5434,
        46.4825 to 30.7233, 51.5 to 31.3, 44.6 to 33.5, 49.84 to 24.03, 47.84 to 35.14,
    )

    @Test fun roundTrip() {
        for ((la, lo) in pts) {
            val m = Mgrs.forward(lo, la)
            val p = Mgrs.inverse(m)
            assertNotNull(m, p)
            val (la2, lo2) = p!!
            assertTrue("$m → $la2,$lo2 vs $la,$lo", abs(la2 - la) < 3e-5 && abs(lo2 - lo) < 4e-5)
            assertEquals(m, Mgrs.forward(lo2, la2)) // the centre of the 1 m square maps back to itself
        }
    }

    @Test fun formats() {
        val m = Mgrs.forward(35.0462, 48.4647) // e.g. 36UXU…
        val spaced = m.substring(0, 3) + " " + m.substring(3, 5) + " " + m.substring(5, 10) + " " + m.substring(10)
        assertEquals(Mgrs.inverse(m), Mgrs.inverse(spaced.lowercase()))
        // without zone → default zone
        assertEquals(Mgrs.inverse(m), Mgrs.inverse(m.substring(3), m.substring(0, 3)))
        // lower precision (10 m) lands within 10 m
        val p10 = Mgrs.inverse(m.substring(0, 5) + m.substring(5, 9) + m.substring(10, 14))!!
        val p1 = Mgrs.inverse(m)!!
        assertTrue(abs(p10.first - p1.first) < 1.5e-4)
        assertNull(Mgrs.inverse("garbage"))
        assertNull(Mgrs.inverse("36UXU123"))
    }
}
