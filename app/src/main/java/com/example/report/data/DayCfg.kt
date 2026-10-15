package com.example.report.data

import kotlinx.serialization.Serializable

/** Per-day counters used by the day/night report. */
@Serializable
data class DayCfg(
    val lost: Int = 0,
    val dmg: Int = 0,
    val props: MutableList<NamedQty> = mutableListOf(NamedQty("Тип А"), NamedQty("Тип Б")),
    val drones: MutableList<NamedQty> = mutableListOf(),
)
