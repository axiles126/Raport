package com.example.report.core

import com.example.report.data.DroneIn
import com.example.report.data.NamedQty

/**
 * «Дронів на позиції» from the drone list: every «Заїхав» counts, every «Переданий» / «Втрачений»
 * takes one off (matched by serial, then tail, then model). Used by the app AND by the scheduled
 * report (no ViewModel), so both always give the same number. Pure — unit-tested.
 */
object DronePos {
    const val IN = "in"
    const val OUT = "out"
    const val LOST = "lost"

    fun count(list: List<DroneIn>): List<NamedQty> =
        remaining(list).groupBy { it.model.trim() }.map { (k, v) -> NamedQty(k, v.size) }

    /** «Заїхав» entries still on the position after transfers / losses. */
    fun remaining(list: List<DroneIn>): List<DroneIn> {
        val ins = list.filter { it.kind == IN }.toMutableList()
        val minus = list.filter { it.kind == OUT || it.kind == LOST }.sortedBy { it.t }
        val byModel = ArrayList<DroneIn>()
        for (m in minus) {
            val i = ins.indexOfFirst {
                (m.code.isNotBlank() && it.code.equals(m.code.trim(), true)) ||
                    (m.code.isBlank() && m.tail.isNotBlank() && it.tail == m.tail)
            }
            if (i >= 0) ins.removeAt(i) else byModel += m
        }
        for (m in byModel) {
            val i = ins.indexOfLast { it.model.trim().equals(m.model.trim(), true) }
            if (i >= 0) ins.removeAt(i)
        }
        return ins
    }

    /** Damaged drones on the position; null = no list in use → the manual day-config number applies. */
    fun damagedForReport(list: List<DroneIn>): Int? =
        if (list.any { it.kind == IN }) remaining(list).count { it.dmg } else null

    /** For the report: null = no list in use → the manual «Дрони» numbers of the day config apply. */
    fun forReport(list: List<DroneIn>): List<NamedQty>? = if (list.any { it.kind == IN }) count(list) else null
}
