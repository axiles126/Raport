package com.example.report.overlay

import android.app.Service
import android.content.Context
import com.example.report.core.Coords
import com.example.report.data.Flight
import com.example.report.data.Store
import kotlinx.serialization.builtins.ListSerializer

/**
 * Process-wide bridge between the UI ([com.example.report.ui.ReportViewModel]) and the
 * background components ([OverlayService], [com.example.report.auto.PilotWatchService],
 * [com.example.report.auto.TemplateFab]).
 *
 * While the ViewModel is alive it installs its callbacks here; otherwise the services fall
 * back to editing the persisted flight list directly.
 *
 * TODO(decomp): original object/file name unknown — R8 merged it into io.ktor.util.TextKt.
 */
object FlightBridge {
    var toggle: (() -> Unit)? = null
    var isFlying: (() -> Boolean)? = null
    var takeoff: (() -> Unit)? = null
    var landing: (() -> Unit)? = null
    var templates: (() -> List<String>)? = null
    var insertTemplate: ((String) -> Unit)? = null
    /** Attaches MGRS to the last line of the active (or latest) flight. */
    var attachCoords: ((String) -> Unit)? = null

    /** Set by [OverlayService]: refreshes the floating button icon. */
    var onStateChanged: (() -> Unit)? = null

    /** Set by PilotWatchService: re-evaluates template-tab visibility when the UI goes fg/bg. */
    var onUiChanged: (() -> Unit)? = null

    @Volatile
    var uiResumed: Boolean = false

    private val flightsSer = ListSerializer(Flight.serializer())

    private fun loadFlights(store: Store): MutableList<Flight> =
        store.load<List<Flight>>("kap_flights", emptyList()).toMutableList()

    private fun saveFlights(store: Store, flights: List<Flight>) {
        store.sp.edit().putString("kap_flights", store.json.encodeToString(flightsSer, flights.toList())).apply()
    }

    fun flying(service: Service): Boolean {
        isFlying?.let { return it() }
        return loadFlights(Store(service)).any { it.lt == null }
    }

    /** Takeoff/landing toggle directly on persisted data (app UI not running). */
    fun storeToggle(context: Context) {
        val store = Store(context)
        val flights = loadFlights(store)
        val active = flights.firstOrNull { it.lt == null }
        if (active != null) {
            flights[flights.indexOf(active)] = active.copy(lt = System.currentTimeMillis())
        } else {
            flights.add(Flight(System.currentTimeMillis()))
        }
        saveFlights(store, flights)
    }

    /**
     * Appends a template line to the active flight directly on persisted data (app UI not running).
     * TODO(decomp): inlined into TemplateFab's click listener; extracted here for symmetry with [storeToggle].
     */
    fun storeInsertTemplate(context: Context, text: String) {
        val store = Store(context)
        val flights = loadFlights(store)
        val idx = flights.indexOfFirst { it.lt == null }
        if (idx >= 0) {
            val now = System.currentTimeMillis()
            val f = flights[idx]
            flights[idx] = f.copy(
                c = Coords.addTemplateLine(now, f.c, text),
                lt = if (Coords.isLand(text)) now else f.lt,
            )
            saveFlights(store, flights)
        }
    }

    /** Fallback for [attachCoords] when the app UI is not running: edits persisted flights. */
    fun storeAttachCoords(context: Context, mgrs: String) {
        val store = Store(context)
        val flights = loadFlights(store)
        val idx = flights.indexOfFirst { it.lt == null }.takeIf { it >= 0 } ?: flights.indices.maxByOrNull { flights[it].t } ?: return
        val f = flights[idx]
        flights[idx] = f.copy(c = attachToLastLine(f.c, mgrs) ?: f.c, mgrs = if (attachToLastLine(f.c, mgrs) == null) mgrs else f.mgrs)
        saveFlights(store, flights)
    }

    /** "… за координатами MGRS" on the last text line; null if there is no text line. */
    fun attachToLastLine(c: String, mgrs: String): String? {
        val lines = ArrayList(c.split("\n"))
        val i = lines.indexOfLast { Coords.TIME_RE.replace(it.trim(), "").let { t -> t.isNotEmpty() && !Coords.isCoord(t) } }
        if (i < 0) return null
        return Coords.withCoords(lines, i, mgrs).joinToString("\n")
    }
}
