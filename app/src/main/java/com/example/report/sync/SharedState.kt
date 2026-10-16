package com.example.report.sync

import com.example.report.data.DayCfg
import com.example.report.data.Event
import com.example.report.data.Field
import com.example.report.data.Flight
import com.example.report.data.Point
import com.example.report.data.Route
import com.example.report.data.DroneIn
import kotlinx.serialization.Serializable

/**
 * Full replicated shift state, exchanged as [Msg.Snapshot] (hub → client) and
 * [Msg.PushState] (client → hub). All properties have defaults, so missing keys decode fine.
 *
 * [rev] is a revision counter maintained by the ViewModel.
 */
@Serializable
data class SharedState(
    val flights: List<Flight> = emptyList(),
    val events: List<Event> = emptyList(),
    val templates: List<String> = emptyList(),
    val fields: List<Field> = emptyList(),
    val points: List<Point> = emptyList(),
    val dayCfg: DayCfg = DayCfg(),
    val name: String = "",
    val leader: String = "",
    val shiftStart: String = "16:00",
    val dayStart: String = "06:00",
    val shiftCode: String = "",
    val sortieCount: String = "",
    val scoutAuto: Boolean = true,
    val rev: Long = 0L,
    val dayEnd: String = "",
    /** Map routes/areas (visibility is per device, not applied from remote). */
    val routes: List<Route> = emptyList(),
    /** «Заїхали / Передані» drone list. */
    val drones: List<DroneIn> = emptyList(),
    /** 2+ = sender syncs [routes]/[drones]; older senders omit them (must not wipe ours). */
    val syncV: Int = 0,
)
