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

    /**
     * «Заїхав» entries still on the position after transfers / losses.
     * A «Переданий»/«Втрачений» with a serial or tail removes only THAT drone (and only if it arrived
     * before); if that drone is not in the «Заїхали» list (old transfers), it changes nothing.
     * Only an entry with neither serial nor tail (e.g. a loss with just the model) takes one of the model.
     */
    fun remaining(list: List<DroneIn>): List<DroneIn> {
        val ins = list.filter { it.kind == IN }.sortedBy { it.t }.toMutableList()
        val minus = list.filter { it.kind == OUT || it.kind == LOST }.sortedBy { it.t }
        for (m in minus) {
            val code = m.code.trim(); val tail = m.tail.trim()
            val i = when {
                code.isNotEmpty() -> ins.indexOfFirst { it.code.trim().equals(code, true) && it.t <= m.t }
                tail.isNotEmpty() -> ins.indexOfFirst { it.tail.trim() == tail && it.t <= m.t }
                else -> ins.indexOfLast { it.model.trim().equals(m.model.trim(), true) && it.t <= m.t }
            }
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
