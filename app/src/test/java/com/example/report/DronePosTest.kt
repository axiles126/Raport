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

    @Test fun noListUsesManual() = assertNull(DronePos.forReport(emptyList()))
}
