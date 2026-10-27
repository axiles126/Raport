package com.example.report.sync

import com.example.report.data.Flight

/**
 * Sanity limits for data received from a peer (an authenticated device can still be buggy or
 * hostile): oversized text, huge lists, negative indices and impossible coordinates are dropped
 * before they reach the state. Pure — unit-tested.
 */
object OpGuard {
    const val MAX_TEXT = 20_000
    const val MAX_SHORT = 500
    const val MAX_LIST = 2_000
    const val MAX_ROUTE_PTS = 5_000
    /** Timestamps must be within ±[MAX_SKEW_MS] of now (catches garbage / wrong-clock values). */
    const val MAX_SKEW_MS = 400L * 24 * 3600 * 1000

    private fun s(v: String?) = v == null || v.length <= MAX_TEXT
    private fun short(v: String?) = v == null || v.length <= MAX_SHORT
    private fun ts(t: Long, now: Long) = t > 0 && kotlin.math.abs(t - now) < MAX_SKEW_MS
    private fun idx(i: Int) = i in 0 until MAX_LIST
    private fun lat(v: Double) = v.isFinite() && v in -90.0..90.0
    private fun lng(v: Double) = v.isFinite() && v in -180.0..180.0

    fun flight(f: Flight, now: Long): Boolean =
        ts(f.t, now) && (f.lt == null || ts(f.lt, now)) && s(f.c) && short(f.mgrs) && s(f.rep) &&
            f.x.size <= 100 && f.x.all { (k, v) -> short(k) && s(v) }

    fun op(op: Op, now: Long = System.currentTimeMillis()): Boolean = when (op) {
        is Op.Takeoff -> op.t == 0L || ts(op.t, now)
        is Op.DeleteFlight -> ts(op.t, now)
        is Op.SetFlightTakeoff -> ts(op.t, now) && short(op.hhmm)
        is Op.SetFlightLanding -> ts(op.t, now) && short(op.hhmm)
        is Op.SetFlightComment -> ts(op.t, now) && s(op.c)
        is Op.SetFlightField -> ts(op.t, now) && short(op.fieldId) && s(op.value)
        is Op.SetFlightRep -> ts(op.t, now) && s(op.rep)
        is Op.InsertTemplate -> short(op.text)
        is Op.AddTemplates -> s(op.raw)
        is Op.RemoveTemplate -> idx(op.index)
        is Op.AddEvent -> short(op.picked)
        is Op.RemoveEvent -> idx(op.index)
        is Op.UpdateEvent -> idx(op.index)
        is Op.AddField -> short(op.name) && short(op.type) && op.opts.size <= 100 && op.opts.all { short(it) }
        is Op.RemoveField -> idx(op.index)
        is Op.AddPoint -> lat(op.lat) && lng(op.lng)
        is Op.UpdatePoint -> short(op.id) && short(op.name) && short(op.color)
        is Op.RemovePoint -> short(op.id)
        is Op.AttachCoordsToFlight -> short(op.mgrs)
        is Op.AttachCoordsToLine -> short(op.mgrs) && idx(op.lineIdx)
        is Op.SetName -> short(op.v)
        is Op.SetLeader -> short(op.v)
        is Op.SetShiftStart -> short(op.v)
        is Op.SetDayStart -> short(op.v)
        is Op.SetDayEnd -> short(op.v)
        is Op.SetShiftCode -> short(op.v)
        is Op.SetSortieCount -> short(op.v)
        is Op.SetRoutes -> op.routes.size <= MAX_LIST && op.routes.all { r ->
            short(r.id) && short(r.name) && short(r.color) && r.pts.size <= MAX_ROUTE_PTS && r.pts.all { (a, b) -> lat(a) && lng(b) }
        }
        is Op.SetDrones -> op.drones.size <= MAX_LIST && op.drones.all { short(it.code) && short(it.model) && short(it.tail) && short(it.kind) }
        is Op.UpsertFlight -> flight(op.f, now) && (op.base == null || flight(op.base, now))
        is Op.SetNeeds -> s(op.text)
        else -> true
    }

    fun state(st: SharedState, now: Long = System.currentTimeMillis()): Boolean =
        st.flights.size <= MAX_LIST && st.flights.all { flight(it, now) } &&
            st.deleted.size <= MAX_LIST * 5 &&
            st.events.size <= MAX_LIST && st.templates.size <= MAX_LIST && st.templates.all { short(it) } &&
            st.fields.size <= 200 && st.points.size <= MAX_LIST && st.points.all { lat(it.lat) && lng(it.lng) } &&
            op(Op.SetRoutes(st.routes), now) && op(Op.SetDrones(st.drones), now) && s(st.needs) &&
            listOf(st.name, st.leader, st.shiftStart, st.dayStart, st.shiftCode, st.sortieCount, st.dayEnd).all { short(it) }
}
