package com.example.report

import com.example.report.core.DronePos
import com.example.report.data.DroneIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DronePosTest {
    @Test fun sixInList() {
        val l = List(6) { DroneIn(t = it.toLong() + 1, code = "SN$it", model = "Matrice 4T") }
        assertEquals(6, DronePos.forReport(l)!!.sumOf { it.qty })
    }

    @Test fun outAndLostSubtract() {
        val l = List(6) { DroneIn(t = it.toLong() + 1, code = "SN$it", model = "Matrice 4T") } +
            DroneIn(t = 10, code = "SN2", kind = "out") + DroneIn(t = 11, model = "Matrice 4T", kind = "lost")
        assertEquals(4, DronePos.forReport(l)!!.sumOf { it.qty })
    }

    @Test fun damagedCounted() {
        val l = List(6) { DroneIn(t = it.toLong() + 1, code = "SN$it", dmg = it < 2) } +
            DroneIn(t = 10, code = "SN0", kind = "out") // a damaged one was handed over → not on position
        assertEquals(1, DronePos.damagedForReport(l))
        assertNull(DronePos.damagedForReport(emptyList()))
    }

    /** Real case: 6 arrived, 11 old transfers of OTHER drones → still 6 on position. */
    @Test fun oldTransfersOfOtherDronesDoNotSubtract() {
        val ins = List(6) { DroneIn(t = 1000L + it, code = "NEW$it", tail = "19$it") }
        val outs = List(11) { DroneIn(t = 10L + it, code = "OLD$it", tail = "18$it", kind = "out") }
        assertEquals(6, DronePos.forReport(ins + outs)!!.sumOf { it.qty })
    }

    @Test fun transferBeforeArrivalDoesNotCount() {
        val l = listOf(DroneIn(t = 5, code = "A", kind = "out"), DroneIn(t = 10, code = "A"))
        assertEquals(1, DronePos.forReport(l)!!.sumOf { it.qty })
    }

    @Test fun noListUsesManual() = assertNull(DronePos.forReport(emptyList()))
}
