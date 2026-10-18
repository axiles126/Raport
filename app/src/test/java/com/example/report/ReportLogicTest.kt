package com.example.report

import com.example.report.core.Coords
import com.example.report.core.Merge3
import com.example.report.core.ReportBuilder
import com.example.report.core.ReportInput
import com.example.report.data.DayCfg
import com.example.report.data.Flight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

/** Report counting / coordinates / merge scenarios. Writes the built reports to build/test-out.txt. */
class ReportLogicTest {
    private val out = StringBuilder()

    private fun at(h: Int, m: Int, dayOffset: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 17, h, m, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, dayOffset)
    }.timeInMillis

    private val tpls = listOf("Загальна розвідка", "Ураження FPV", "Супровід НРК")

    private fun input(mode: String, flights: List<Flight>, now: Long) = ReportInput(
        mode = mode, name = "Тест", leader = "", flights = flights, events = emptyList(), templates = tpls,
        fields = emptyList(), dayCfg = DayCfg(), shiftStart = "06:00", dayStart = "06:00", shiftCode = "",
        sortieCount = "", scoutAuto = true, showAir = false, showCountShift = true, now = now, curRec = null,
        activeRec = null, flying = false, startTs = 0L, dayEnd = "18:00", shiftDetailed = false,
    )

    /** As the app does: template line, or coords onto the last text line. */
    private fun tpl(c: String, t: Long, text: String) = Coords.addTemplateLine(t, c, text)
    private fun coords(c: String, mgrs: String): String {
        val lines = c.split("\n")
        val i = lines.indexOfLast { Coords.TIME_RE.replace(it.trim(), "").let { s -> s.isNotEmpty() && !Coords.isCoord(s) } }
        return Coords.withCoords(lines, i, mgrs).joinToString("\n")
    }

    private fun report(name: String, mode: String, flights: List<Flight>, now: Long): String {
        val r = ReportBuilder.finalize(mode, ReportBuilder.build(input(mode, flights, now)))
        out.append("==== $name [$mode]\n").append(flights.joinToString("\n---\n") { it.c }).append("\n>>>>\n").append(r).append("\n\n")
        File("build/test-out.txt").appendText(out.toString()); out.clear()
        return r
    }

    @Test fun twoStrikesSameFlightMerged() {
        var c = ""
        c = tpl(c, at(10, 0), "Загальна розвідка")
        c = tpl(c, at(10, 5), "Ураження FPV")
        c = tpl(c, at(10, 7), "Ураження FPV")      // → ×2
        c = coords(c, "37U DQ 1111 2222")
        c = coords(c, "37U DQ 3333 4444")
        val f = listOf(Flight(at(10, 0), c, at(10, 30)))
        val day = report("same flight ×2 + 2 coords", "day", f, at(12, 0))
        assertTrue(day, day.contains("Ураження FPV - 2"))
        val shift = report("same flight ×2 + 2 coords", "shift", f, at(12, 0))
        assertTrue(shift, shift.contains("1111 2222") && shift.contains("3333 4444"))
    }

    @Test fun twoStrikesCoordsBetween() {
        var c = ""
        c = tpl(c, at(10, 5), "Ураження FPV")
        c = coords(c, "37U DQ 1111 2222")
        c = tpl(c, at(10, 9), "Ураження FPV")      // line has coords → new line
        c = coords(c, "37U DQ 3333 4444")
        val f = listOf(Flight(at(10, 0), c, at(10, 30)))
        val day = report("coords between", "day", f, at(12, 0))
        assertTrue(day, day.contains("Ураження FPV - 2"))
        val shift = report("coords between", "shift", f, at(12, 0))
        assertTrue(shift, shift.contains("1111 2222") && shift.contains("3333 4444"))
    }

    @Test fun twoStrikesTwoFlights() {
        val f1 = Flight(at(9, 0), coords(tpl("", at(9, 5), "Ураження FPV"), "37U DQ 1111 2222"), at(9, 30))
        val f2 = Flight(at(11, 0), coords(tpl("", at(11, 5), "Ураження FPV"), "37U DQ 3333 4444"), at(11, 30))
        val day = report("two flights", "day", listOf(f1, f2), at(12, 0))
        assertTrue(day, day.contains("Ураження FPV - 2"))
        val shift = report("two flights", "shift", listOf(f1, f2), at(12, 0))
        assertTrue(shift, shift.contains("1111 2222") && shift.contains("3333 4444"))
    }

    @Test fun flightLevelCoords() {
        // coords picked before any template line → flight-level mgrs
        val f = Flight(at(10, 0), tpl(tpl("", at(10, 5), "Ураження FPV"), at(10, 8), "Ураження FPV"), at(10, 30), mgrs = "37U DQ 1111 2222")
        report("flight-level mgrs", "day", listOf(f), at(12, 0))
        val shift = report("flight-level mgrs", "shift", listOf(f), at(12, 0))
        assertTrue(shift, shift.contains("1111 2222"))
    }

    @Test fun concurrentTemplateOnTwoDevices() {
        val base = Flight(at(10, 0), "10:01 Загальна розвідка", null, u = 1)
        val hub = base.copy(c = base.c + "\n10:05 Ураження FPV", u = 2)       // added on the hub / пульт
        val cli = base.copy(c = base.c + "\n10:06 Супровід НРК", u = 3)       // added on the phone
        val m = Merge3.mergeFlight(base, hub, cli)
        assertTrue(m.c, m.c.contains("Ураження FPV") && m.c.contains("Супровід НРК"))
        // same template tapped on both → counts add up
        val b2 = Flight(at(10, 0), "10:05 Ураження FPV", null)
        val m2 = Merge3.mergeFlight(b2, b2.copy(c = "10:05 Ураження FPV ×2"), b2.copy(c = "10:05 Ураження FPV ×2"))
        assertEquals("10:05 Ураження FPV ×3", m2.c)
        // landing on the phone, template on the hub → both kept
        val m3 = Merge3.mergeFlight(base, hub, base.copy(lt = at(10, 30)))
        assertEquals(at(10, 30), m3.lt)
        assertTrue(m3.c.contains("Ураження FPV"))
    }
}
