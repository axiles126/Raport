package com.example.report.sync

import com.example.report.data.DayCfg
import com.example.report.data.DroneIn
import com.example.report.data.Flight
import com.example.report.data.Route
import kotlinx.serialization.Serializable

/**
 * A single mutation of the shared shift state, sent client → hub inside [Msg.Operation].
 *
 * Serialized polymorphically through [Proto.json] (class discriminator `"k"`); no `@SerialName`
 * is used, so the discriminator value is the fully-qualified name, e.g.
 * `{"k":"com.example.report.sync.Op.Takeoff","t":1718000000000}`.
 *
 * Ops are applied on the hub by `ReportViewModel` (the `applyOp` callback wired into
 * [SyncManager.applyOp]); the dispatch `when (op) { … }` lives there, not in this package.
 *
 * Objects with no payload are modelled as data classes with an optional `dummy` field
 * (kotlinx.serialization data classes need at least one property).
 */
@Serializable
sealed class Op {

    // ---- flights -------------------------------------------------------------------------

    /** Take-off at [t] (epoch ms); `t <= 0` means "now" on the applying side. */
    @Serializable
    data class Takeoff(val t: Long) : Op()

    /** Landing of the current flight; [t] is the landing time or `null` for "now". */
    @Serializable
    data class Landing(val t: Long? = null) : Op()

    @Serializable
    data class ForceReset(val dummy: Int = 0) : Op()

    @Serializable
    data class AddFlight(val dummy: Int = 0) : Op()

    /** Flights are identified by their take-off timestamp [t]. */
    @Serializable
    data class DeleteFlight(val t: Long) : Op()

    @Serializable
    data class SetFlightTakeoff(val t: Long, val hhmm: String) : Op()

    @Serializable
    data class SetFlightLanding(val t: Long, val hhmm: String?) : Op()

    @Serializable
    data class SetFlightComment(val t: Long, val c: String) : Op()

    @Serializable
    data class SetFlightField(val t: Long, val fieldId: String, val value: String) : Op()

    @Serializable
    data class SetFlightRep(val t: Long, val rep: String?) : Op()

    // ---- templates -----------------------------------------------------------------------

    @Serializable
    data class InsertTemplate(val text: String) : Op()

    @Serializable
    data class AddTemplates(val raw: String) : Op()

    @Serializable
    data class RemoveTemplate(val index: Int) : Op()

    // ---- events --------------------------------------------------------------------------

    @Serializable
    data class AddEvent(val picked: String) : Op()

    /** Partial update: `null` fields keep the existing value (except [t2], which is always replaced). */
    @Serializable
    data class UpdateEvent(
        val index: Int,
        val t: Long?,
        val t2: Long?,
        val text: String?,
        val mgrs: String?,
    ) : Op()

    @Serializable
    data class RemoveEvent(val index: Int) : Op()

    // ---- custom fields -------------------------------------------------------------------

    @Serializable
    data class AddField(val name: String, val type: String, val opts: List<String>) : Op()

    @Serializable
    data class RemoveField(val index: Int) : Op()

    // ---- day config ----------------------------------------------------------------------

    @Serializable
    data class SetDayCfg(val cfg: DayCfg) : Op()

    // ---- map points ----------------------------------------------------------------------

    @Serializable
    data class AddPoint(val lat: Double, val lng: Double) : Op()

    @Serializable
    data class UpdatePoint(val id: String, val name: String?, val color: String?) : Op()

    @Serializable
    data class RemovePoint(val id: String) : Op()

    @Serializable
    data class ClearPoints(val dummy: Int = 0) : Op()

    @Serializable
    data class AttachCoordsToFlight(val mgrs: String) : Op()

    @Serializable
    data class AttachCoordsToLine(val mgrs: String, val lineIdx: Int) : Op()

    // ---- report header -------------------------------------------------------------------

    @Serializable
    data class SetName(val v: String) : Op()

    @Serializable
    data class SetLeader(val v: String) : Op()

    @Serializable
    data class SetShiftStart(val v: String) : Op()

    @Serializable
    data class SetDayStart(val v: String) : Op()

    @Serializable
    data class SetDayEnd(val v: String) : Op()

    @Serializable
    data class SetShiftCode(val v: String) : Op()

    @Serializable
    data class SetSortieCount(val v: String) : Op()

    @Serializable
    data class SetScoutAuto(val v: Boolean) : Op()

    @Serializable
    data class ResetShift(val dummy: Int = 0) : Op()

    // ---- map routes / drone list (whole-list replace; older versions ignore unknown ops) ----

    @Serializable
    data class SetRoutes(val routes: List<Route>) : Op()

    @Serializable
    data class SetDrones(val drones: List<DroneIn>) : Op()

    /** Whole flight from a client (newer [Flight.u] wins on the hub). */
    @Serializable
    data class UpsertFlight(val f: Flight) : Op()
}
