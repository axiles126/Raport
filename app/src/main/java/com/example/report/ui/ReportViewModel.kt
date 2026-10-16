package com.example.report.ui

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.report.alarm.ReportAlarmReceiver
import com.example.report.core.Coords
import com.example.report.core.Loss
import com.example.report.core.Merge3
import com.example.report.core.Mgrs
import com.example.report.core.ReportBuilder
import com.example.report.core.ReportInput
import com.example.report.core.TimeFmt
import com.example.report.data.DailyAlarm
import com.example.report.data.DayCfg
import com.example.report.data.DroneIn
import com.example.report.data.Route
import com.example.report.data.StrikeDraft
import com.example.report.data.Event
import com.example.report.data.Field
import com.example.report.data.Flight
import com.example.report.data.NamedQty
import com.example.report.data.Point
import com.example.report.data.ScheduledSend
import com.example.report.data.Store
import com.example.report.overlay.FlightBridge
import com.example.report.sync.NetUtil
import com.example.report.sync.NsdHelper
import com.example.report.sync.Op
import com.example.report.sync.SharedState
import com.example.report.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlin.random.Random
import kotlin.random.nextInt

class ReportViewModel(application: Application) : AndroidViewModel(application) {

    /** Loss-of-aircraft report being composed on the loss screen. */
    data class LossDraft(
        val flightT: Long,
        val cause: String,
        val at: Long,
        val coords: String,
        val pilot: String,
        /** Drone model (e.g. "Matrice 4T"). */
        val drone: String,
        /** 4 digits shown in brackets. */
        val tail: String = "",
        /** Drone number (from the QR list or typed). */
        val serial: String = "",
        val surname: String = "",
        /** t of the "lost" drone-list entry created for this loss (position count source). */
        val lostT: Long = 0L,
    )

    val store = Store(application)
    val DAY = 86_400_000L

    // ---- network sync ------------------------------------------------------------------------

    var netMode by mutableStateOf(NetMode.SOLO)
    var netStatus by mutableStateOf("")
    var netPeers by mutableStateOf(0)
    var netCode by mutableStateOf("")

    val sync = SyncManager(onMain = { block -> viewModelScope.launch(Dispatchers.Main.immediate) { block() } }).apply {
        provideState = { sharedState() }
        applyOp = { op -> applyRemoteOp(op) }
        applySnapshot = { st -> applySnapshot(st) }
        mergeState = { st -> mergeState(st) }
        onStatus = { netStatus = it }
        onToast = { text, cls -> setStatus(text, cls) }
        onPeersChanged = { netPeers = it }
        onConnectFailing = { lookForHub() }
    }
    val nsd = NsdHelper(application)
    val foundHubs = mutableStateListOf<NsdHelper.Found>()

    // ---- network comes back: client reconnects at once, hub re-announces itself ----
    private val connMgr = application.getSystemService(android.net.ConnectivityManager::class.java)
    private val netCb = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            viewModelScope.launch(Dispatchers.Main.immediate) { onNetworkBack() }
        }
    }
    init { runCatching { connMgr?.registerDefaultNetworkCallback(netCb) } }

    /** Neutral mDNS name: no app name / phone model broadcast on the network. */
    private fun hubServiceName(): String {
        var id = store.sp.getString("kap_hubid", "") ?: ""
        if (id.isEmpty()) { id = java.util.UUID.randomUUID().toString().take(4); store.putString("kap_hubid", id) }
        return "hub-$id"
    }

    private fun onNetworkBack() {
        when (netMode) {
            NetMode.CLIENT -> sync.reconnectNow()
            NetMode.HUB -> nsd.register(hubServiceName(), HUB_PORT)
            else -> Unit
        }
    }

    /** Client keeps failing (hub IP may have changed after Wi-Fi reconnect): find the hub via NSD and switch. */
    private fun lookForHub() {
        if (netMode != NetMode.CLIENT) return
        val code = store.sp.getString("kap_hubcode", "") ?: ""
        if (code.isEmpty()) return
        nsd.startDiscovery { found ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (netMode == NetMode.CLIENT && found.ip != sync.connectedHost) {
                    nsd.stopDiscovery()
                    setStatus("Хаб знайдено за новою адресою " + found.ip, "ok")
                    connectToHub(found.ip, code)
                }
            }
        }
    }

    /** Hub: re-broadcast snapshot after a local change. Client: push the op to the hub. */
    private var onBroadcast: (() -> Unit)? = null
    private var onSendOp: ((Op) -> Unit)? = null

    /** True while applying ops/snapshots received from the network (suppresses re-emit). */
    private var applyingRemote = false
    /** Applying remote flight data: keep the incoming [Flight.u] instead of stamping now. */
    private var adoptingRemote = false
    /** Flights as last persisted (without u) — to detect what changed. */
    private var lastSaved: Map<Long, Flight> = emptyMap()
    /** Deleted flights: takeoff ts → deletion time. */
    private val deletedT = HashMap<Long, Long>()
    /** Client: [Flight.u] of each flight in the last hub snapshot (to tell whether the hub changed it since). */
    private val hubU = HashMap<Long, Long>()

    // ---- shift data --------------------------------------------------------------------------

    val flights = mutableStateListOf<Flight>()
    val templates = mutableStateListOf<String>()
    val events = mutableStateListOf<Event>()

    /** «Заїхав дрон» scans in order (number = position + 1). Synced with the hub (SharedState.drones); not in shift reports. */
    val droneIns = mutableStateListOf<DroneIn>()
    /** Report archive manual edits (see archiveText). */
    val archEdits = androidx.compose.runtime.mutableStateMapOf<String, ArchEdit>()
    var droneModel by mutableStateOf("Matrice 4T")

    /** «Ураження» report draft + map coordinate target for it. */
    var strike by mutableStateOf(StrikeDraft())
    var strikeCoordTarget by mutableStateOf(false)

    /** Route being drawn on the map (lat, lng); [routeStrokes] = start index of each tap/stroke (for undo). */
    val route = mutableStateListOf<Pair<Double, Double>>()
    val routeStrokes = mutableStateListOf<Int>()
    /** What is being drawn: false = route (line), true = closed area. */
    var drawClosed by mutableStateOf(false)
    /** Id of the saved route loaded for editing (null = new). */
    var routeEditId by mutableStateOf<String?>(null)
    /** Saved routes, persisted. */
    val routes = mutableStateListOf<Route>()
    val fields = mutableStateListOf<Field>()
    val points = mutableStateListOf<Point>()
    var dayCfg by mutableStateOf(DayCfg())
    var name by mutableStateOf("")
    var leader by mutableStateOf("")

    /** Report period: "current" (current sortie), "day", "night", "shift", "week". */
    var mode by mutableStateOf("current")
    var showAir by mutableStateOf(false)
    var bigEdit by mutableStateOf(false)
    var showCountShift by mutableStateOf(false)
    var shiftDetailed by mutableStateOf(true)
    var overlayEnabled by mutableStateOf(false)
    var scoutAuto by mutableStateOf(true)
    var shiftStart by mutableStateOf("16:00")
    var dayStart by mutableStateOf("06:00")
    var dayEnd by mutableStateOf("")
    var shiftCode by mutableStateOf("")
    var sortieCount by mutableStateOf("")
    var baseMap by mutableStateOf("sat")
    var themeMode by mutableStateOf("system")

    // ---- sending -----------------------------------------------------------------------------

    var autoSend by mutableStateOf(false)
    /** Incremented to make the UI open the share sheet. */
    var shareTrigger by mutableStateOf(0)
    var autoWhatsApp by mutableStateOf(false)
    var waPhone by mutableStateOf("")
    var autoSignal by mutableStateOf(false)
    var needsText by mutableStateOf("")
    var needsNumbered by mutableStateOf(true)
    /** Incremented to make the UI send directly to WhatsApp/Signal. */
    var directTrigger by mutableStateOf(0)
    val schedules = mutableStateListOf<ScheduledSend>()
    var scheduleTest by mutableStateOf<ScheduledSend?>(null)
    var scheduleTestTrigger by mutableStateOf(0)

    // ---- alarms ------------------------------------------------------------------------------

    var alarmAt by mutableStateOf(0L)
    val daily = mutableStateListOf<DailyAlarm>()
    var dailyLast by mutableStateOf(0L)

    // ---- flight timer ------------------------------------------------------------------------

    var flying by mutableStateOf(false)
    var startTs by mutableStateOf(0L)
    /** Flight explicitly selected for the "current" report (null → active/last). */
    var curRecT by mutableStateOf<Long?>(null)
    var clock by mutableStateOf("00:00")
    var clockLive by mutableStateOf(false)
    var flightInfo by mutableStateOf("таймер зупинено")
    private var activeRecT: Long? = null
    private var takeoffStr = ""
    private var landingStr = ""
    private var lastFlightMs = 0L
    private var timerJob: Job? = null

    // ---- status / preview --------------------------------------------------------------------

    var status by mutableStateOf("")
    /** "ok" | "err" | "dim" */
    var statusCls by mutableStateOf("dim")
    var previewVisible by mutableStateOf(true)
    var previewText by mutableStateOf("")
    /** Auto text the (possibly edited) preview was derived from — base for the 3-way merge. */
    private var previewBase = ""
    /** Same for per-flight saved reports in «Зараз» mode (in memory). */
    private val repBase = HashMap<Long, String>()
    var previewEdited by mutableStateOf(false)
    /** Bumped on every data change; read by [buildReport] so Compose recomputes. */
    var dataRev by mutableStateOf(0)

    var lossDraft by mutableStateOf<LossDraft?>(null)
    /** Index of the event awaiting coordinates picked on the map. */
    var eventCoordTarget by mutableStateOf<Int?>(null)
    var ringing by mutableStateOf(false)
    var ringText by mutableStateOf("")

    init {
        val sp = store.sp
        flights.addAll(store.load<List<Flight>>("kap_flights", emptyList()).map { normFlight(it) })
        deletedT.putAll(store.load<Map<Long, Long>>("kap_flight_del", emptyMap()))
        lastSaved = flights.associate { it.t to it.copy(u = 0L) }
        templates.addAll(store.load<List<String>>("kap_templates", emptyList()))
        events.addAll(store.load<List<Event>>("kap_events", emptyList()))
        fields.addAll(store.load<List<Field>>("kap_fields", emptyList()))
        points.addAll(store.load<List<Point>>("kap_points", emptyList()))
        dayCfg = store.load<DayCfg>("kap_daycfg", DayCfg())
        if (!sp.getBoolean("kap_lost_auto", false)) {
            // v2.8: losses are now counted automatically by time; dayCfg.lost is only a manual extra.
            dayCfg = dayCfg.copy(lost = 0)
            store.putStringNow("kap_daycfg", store.json.encodeToString(DayCfg.serializer(), dayCfg))
            sp.edit().putBoolean("kap_lost_auto", true).apply()
        }
        name = sp.getString("kap_name", "") ?: ""
        leader = sp.getString("kap_leader", "") ?: ""
        mode = sp.getString("kap_reportmode", "current") ?: "current"
        if (mode !in listOf("day", "night", "shift", "week")) mode = "current"
        showAir = sp.getBoolean("kap_showair", false)
        bigEdit = sp.getBoolean("kap_bigedit", false)
        showCountShift = sp.getBoolean("kap_countshift", false)
        shiftDetailed = sp.getBoolean("kap_shiftdetail", true)
        overlayEnabled = sp.getBoolean("kap_overlay", false)
        scoutAuto = sp.getBoolean("kap_scout", true)
        shiftStart = sp.getString("kap_shiftstart", "16:00") ?: "16:00"
        dayStart = sp.getString("kap_daystart", "06:00") ?: "06:00"
        dayEnd = sp.getString("kap_dayend", "") ?: ""
        shiftCode = sp.getString("kap_shiftcode", "") ?: ""
        sortieCount = sp.getString("kap_sorties", "") ?: ""
        baseMap = sp.getString("kap_basemap", "sat") ?: "sat"
        themeMode = sp.getString("kap_theme", "system") ?: "system"
        autoSend = sp.getBoolean("kap_autosend", false)
        autoWhatsApp = sp.getBoolean("kap_wa", false)
        waPhone = sp.getString("kap_waphone", "") ?: ""
        autoSignal = sp.getBoolean("kap_signal", false)
        needsText = sp.getString("kap_needs", "") ?: ""
        needsNumbered = sp.getBoolean("kap_needs_num", true)
        droneIns.addAll(store.load<List<DroneIn>>("kap_dronein", emptyList()))
        droneModel = sp.getString("kap_drone_model", "Matrice 4T") ?: "Matrice 4T"
        strike = store.load<StrikeDraft>("kap_strike", StrikeDraft())
        routes.addAll(store.load<List<Route>>("kap_routes", emptyList()))
        archEdits.putAll(store.load<Map<String, ArchEdit>>("kap_arch_edits", emptyMap()))

        val savedSchedules = store.load<List<ScheduledSend>>("kap_schedules", emptyList())
        schedules.clear()
        if (savedSchedules.isNotEmpty()) {
            schedules.addAll(savedSchedules)
        } else {
            schedules.addAll(
                listOf(
                    ScheduledSend("night", "05:50", "night", false, "whatsapp", "Група день/ніч"),
                    ScheduledSend("day", "17:50", "day", false, "whatsapp", "Група день/ніч"),
                    ScheduledSend("shift", "15:50", "shift", false, "whatsapp", "Група доба"),
                ),
            )
            saveSchedules()
        }

        alarmAt = sp.getLong("kap_alarm", 0L)
        daily.addAll(
            store.load<List<DailyAlarm>>("kap_almdaily",
                listOf(DailyAlarm("05:45"), DailyAlarm("17:45")),
            ),
        )
        dailyLast = sp.getLong("kap_almlast", System.currentTimeMillis())
        if (sp.contains("kap_wxcache")) sp.edit().remove("kap_wxcache").apply()

        syncFlyingState()
        ReportAlarmReceiver.rescheduleAll(getApplication<Application>())

        FlightBridge.toggle = { if (hasActiveFlight()) landing(true) else takeoff() }
        FlightBridge.isFlying = { hasActiveFlight() }
        FlightBridge.takeoff = { takeoff() }
        FlightBridge.landing = { landing(true) }
        FlightBridge.cancelFlight = { cancelActiveFlight() }
        FlightBridge.templates = { templates.toList() }
        FlightBridge.insertTemplate = { insertTemplate(it) }
        FlightBridge.attachCoords = { attachCoordsToLast(it) }

        updatePreview(true)

        // One-shot alarm + daily alarms, checked every second.
        viewModelScope.launch {
            while (true) {
                delay(1000)
                if (alarmAt != 0L && System.currentTimeMillis() >= alarmAt) {
                    val at = alarmAt
                    alarmAt = 0L
                    store.sp.edit().putLong("kap_alarm", alarmAt).apply()
                    ringing = true
                    ringText = "⏰ Будильник " + TimeFmt.hm(at)
                }
                val now = System.currentTimeMillis()
                for (d in daily) {
                    if (!d.on) continue
                    val ts = TimeFmt.tsFromTime(now, d.t)
                    if (ts <= now && ts > dailyLast) {
                        dailyLast = ts
                        store.sp.edit().putLong("kap_almlast", ts).apply()
                        if (now - ts <= 900_000L) {
                            ringing = true
                            ringText = "⏰ " + d.t + " — щоденний будильник"
                        }
                    }
                }
            }
        }
        // Periodic autosave (prunes >8-day-old entries) + preview refresh.
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                saveFlights()
                saveEvents()
                syncPreview()
            }
        }
    }

    override fun onCleared() {
        runCatching { connMgr?.unregisterNetworkCallback(netCb) }
        sync.shutdown()
        nsd.stop()
        FlightBridge.toggle = null
        FlightBridge.isFlying = null
        FlightBridge.takeoff = null
        FlightBridge.landing = null
        FlightBridge.cancelFlight = null
        FlightBridge.templates = null
        FlightBridge.insertTemplate = null
        FlightBridge.attachCoords = null
    }

    // ==== lookups =============================================================================

    fun activeRec(): Flight? {
        val t = activeRecT ?: return null
        return flights.firstOrNull { it.t == t }
    }

    /** Flight the "current" report is about: selected one, else the active one, else the last. */
    fun curRec(): Flight? {
        curRecT?.let { t ->
            flights.firstOrNull { it.t == t }?.let { return it }
            curRecT = null
        }
        return activeRec() ?: flights.lastOrNull()
    }

    fun hasActiveFlight(): Boolean = flights.any { it.lt == null }

    /** Time window new records fall into for the selected report mode. */
    fun recWindow(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        if (mode == "shift") return ReportBuilder.shiftBounds(now, shiftStart)
        if (mode == "day" || mode == "night") return ReportBuilder.dayNightWindow(mode, dayStart, dayEnd, now)
        return Pair(now - 7 * DAY, now + DAY)
    }

    fun sharedState(): SharedState = SharedState(
        flights = flights.toList(),
        events = events.toList(),
        templates = templates.toList(),
        fields = fields.toList(),
        points = points.toList(),
        dayCfg = dayCfg,
        name = name,
        leader = leader,
        shiftStart = shiftStart,
        dayStart = dayStart,
        shiftCode = shiftCode,
        sortieCount = sortieCount,
        scoutAuto = scoutAuto,
        rev = dataRev.toLong(),
        dayEnd = dayEnd,
        routes = routes.toList(),
        drones = droneIns.toList(),
        syncV = 3,
        deleted = deletedT.keys.toList(),
    )

    // ==== report ==============================================================================

    fun buildReport(stripRep: Boolean = false): String {
        dataRev // read so Compose callers recompose on data changes
        val cur = curRec().let { if (stripRep) it?.copy(rep = null) else it }
        return ReportBuilder.build(
            ReportInput(
                mode = mode,
                name = name,
                leader = leader,
                flights = flights.toList(),
                events = events.toList(),
                templates = templates.toList(),
                fields = fields.toList(),
                dayCfg = dayCfg,
                shiftStart = shiftStart,
                dayStart = dayStart,
                shiftCode = shiftCode,
                sortieCount = sortieCount,
                scoutAuto = scoutAuto,
                showAir = showAir,
                showCountShift = showCountShift,
                now = System.currentTimeMillis(),
                curRec = cur,
                activeRec = activeRec(),
                flying = flying,
                startTs = startTs,
                dayEnd = dayEnd,
                shiftDetailed = shiftDetailed,
                posDrones = reportPosDrones(),
            ),
        )
    }

    /** Text to send/copy: the editor text (with manual edits, refreshed from the data). */
    fun textForSend(): String {
        syncPreview()
        return previewText.ifEmpty { buildReport() }
    }

    // ==== report archive (past days: День / Ніч / Доба) =====================================

    /** Manual edits of archived reports: key "mode|dayMidnight" → (auto base, edited text). */
    @kotlinx.serialization.Serializable
    data class ArchEdit(val base: String, val text: String)

    fun archKey(mode: String, day: Long) = "$mode|$day"

    /** Midnight of [t]'s local date. */
    fun dayStartOf(t: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = t
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Last [KEEP_DAYS] dates, newest first. */
    fun archiveDays(): List<Long> {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = dayStartOf(System.currentTimeMillis()) }
        return List(KEEP_DAYS) { i -> if (i > 0) c.add(java.util.Calendar.DAY_OF_MONTH, -1); c.timeInMillis }
    }

    /** A moment inside the [mode] period that starts on date [day]. */
    private fun archNow(mode: String, day: Long): Long {
        val inDay = day + (minutesOfHm(dayStart) ?: 360) * 60_000L + 60_000L
        return when (mode) {
            "day" -> inDay
            "night" -> ReportBuilder.dayNightWindow("day", dayStart, dayEnd, inDay).let { it.second + 60_000L }
            else -> day + (minutesOfHm(shiftStart) ?: 960) * 60_000L + 60_000L
        }
    }

    private fun minutesOfHm(s: String): Int? {
        val p = s.split(":"); val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        return h * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun archiveWindow(mode: String, day: Long): Pair<Long, Long> {
        val now = archNow(mode, day)
        return if (mode == "shift") ReportBuilder.shiftBounds(now, shiftStart)
        else ReportBuilder.dayNightWindow(mode, dayStart, dayEnd, now).let { w ->
            if (mode == "night" && w.first > now) Pair(w.first - DAY, w.second - DAY) else w
        }
    }

    fun archiveFlights(mode: String, day: Long): List<Flight> {
        dataRev
        val (a, b) = archiveWindow(mode, day)
        return flights.filter { it.t in a until b }.sortedBy { it.t }
    }

    /** Auto-built report of a past period (counters that aren't stored per day start at 0). */
    fun archiveAuto(mode: String, day: Long): String {
        dataRev
        return ReportBuilder.build(
            ReportInput(
                mode = mode, name = name, leader = leader,
                flights = flights.toList(), events = events.toList(), templates = templates.toList(),
                fields = fields.toList(),
                dayCfg = DayCfg(props = dayCfg.props.map { it.copy(qty = 0) }.toMutableList(), drones = dayCfg.drones.toMutableList()),
                shiftStart = shiftStart, dayStart = dayStart, shiftCode = shiftCode, sortieCount = "",
                scoutAuto = scoutAuto, showAir = showAir, showCountShift = showCountShift,
                now = archNow(mode, day), curRec = null, activeRec = null, flying = false, startTs = 0L,
                dayEnd = dayEnd, shiftDetailed = shiftDetailed, posDrones = reportPosDrones(),
            ),
        )
    }

    /** Shown text: auto, or the manual edit with later data changes merged in. */
    fun archiveText(mode: String, day: Long): String {
        val auto = archiveAuto(mode, day)
        val e = archEdits[archKey(mode, day)] ?: return auto
        return Merge3.merge(e.base, e.text, auto)
    }

    fun editArchive(mode: String, day: Long, text: String) {
        archEdits[archKey(mode, day)] = ArchEdit(archiveAuto(mode, day), text)
        saveArchEdits()
    }

    fun resetArchive(mode: String, day: Long) {
        archEdits.remove(archKey(mode, day)); saveArchEdits()
    }

    private fun saveArchEdits() {
        val cutoff = System.currentTimeMillis() - (KEEP_DAYS + 1) * DAY
        archEdits.keys.filter { (it.substringAfter('|').toLongOrNull() ?: 0L) < cutoff }.forEach { archEdits.remove(it) }
        store.putString("kap_arch_edits", store.json.encodeToString(
            kotlinx.serialization.builtins.MapSerializer(String.serializer(), ArchEdit.serializer()), archEdits.toMap()))
    }

    /** Single-flight report («Зараз» format) for the archive: saved edit or auto. */
    fun flightReport(f: Flight): String {
        f.rep?.takeIf { it.isNotEmpty() }?.let { return it }
        return ReportBuilder.build(
            ReportInput(
                mode = "current", name = name, leader = leader,
                flights = flights.toList(), events = events.toList(), templates = templates.toList(),
                fields = fields.toList(), dayCfg = dayCfg, shiftStart = shiftStart, dayStart = dayStart,
                shiftCode = shiftCode, sortieCount = sortieCount, scoutAuto = scoutAuto, showAir = showAir,
                showCountShift = showCountShift, now = System.currentTimeMillis(), curRec = f.copy(rep = null),
                activeRec = activeRec(), flying = flying, startTs = startTs, dayEnd = dayEnd, shiftDetailed = shiftDetailed,
            ),
        )
    }

    /** Rebuilds the preview unless the user edited it ([force] discards manual edits). */
    fun updatePreview(force: Boolean) {
        if (previewEdited && !force) return
        if (force) previewEdited = false
        val text = buildReport()
        previewBase = buildReport(stripRep = true)
        if (previewText == text) return
        previewText = text
    }

    /**
     * Data changed (locally or from another device): refresh the preview. Manual edits are kept —
     * only the parts the data changed are carried into the edited text (3-way line merge).
     */
    fun syncPreview() {
        if (!previewEdited) { updatePreview(false); return }
        val auto = buildReport(stripRep = true)
        if (mode == "current") {
            val rec = curRec()
            val rep = rec?.rep
            if (rec == null || rep.isNullOrEmpty()) { previewEdited = false; updatePreview(false); return }
            val base = repBase[rec.t] ?: return
            val merged = Merge3.merge(base, rep, auto)
            repBase[rec.t] = auto
            if (merged != rep) {
                val i = flights.indexOfFirst { it.t == rec.t }
                if (i >= 0) flights[i] = flights[i].copy(rep = merged)
                saveFlights()
            }
            previewText = merged
            return
        }
        if (previewBase.isEmpty()) return
        previewText = Merge3.merge(previewBase, previewText, auto)
        previewBase = auto
    }

    fun refreshPreviewNow() {
        if (previewEdited && previewText != buildReport()) {
            setStatus("Звіт оновлено — ручні правки скинуто", "ok")
        }
        previewEdited = false
        updatePreview(true)
    }

    fun reportEdited(): Boolean {
        if (previewEdited) return true
        if (mode != "current") return false
        return !curRec()?.rep.isNullOrEmpty()
    }

    fun clearSavedReport(t: Long) {
        val i = flights.indexOfFirst { it.t == t }
        if (i >= 0) flights[i] = flights[i].copy(rep = null)
        saveFlights()
        if (curRec()?.t == t) {
            previewEdited = false
            updatePreview(true)
        }
        setStatus("Повернуто автоматичний звіт", "ok")
    }

    fun setStatus(text: String, cls: String) {
        status = text
        statusCls = cls
    }

    fun bump() {
        dataRev++
    }

    fun afterData() {
        bump()
        syncPreview()
    }

    private fun fireAutoSend() {
        if (autoSend) {
            shareTrigger++
            setStatus("Автонадсилання звіту…", "ok")
        }
        if (autoWhatsApp || autoSignal) directTrigger++
    }

    // ==== flights =============================================================================

    fun takeoff() {
        if (netMode != NetMode.CLIENT) {
            if (hasActiveFlight()) {
                setStatus("Зайнято — вже є активний виліт", "err")
                return
            }
            doTakeoff(System.currentTimeMillis())
            emit(Op.Takeoff(0L))
            return
        }
        if (hasActiveFlight()) {
            setStatus("Зайнято — вже є активний виліт", "err")
            return
        }
        doTakeoff(System.currentTimeMillis()) // sent to the hub as UpsertFlight
    }

    /** @param autoSend fire the configured auto-send after landing. */
    fun landing(autoSend: Boolean) {
        val wasActive = hasActiveFlight()
        if (netMode != NetMode.CLIENT) {
            doLanding()
            emit(Op.Landing(null))
            if (autoSend) fireAutoSend()
            return
        }
        doLanding() // sent to the hub as UpsertFlight
        if (wasActive && autoSend) fireAutoSend()
    }

    fun doTakeoff(t: Long) {
        curRecT = null
        flights.add(Flight(t))
        activeRecT = t
        saveFlights()
        syncFlyingState()
        refreshPreviewNow()
    }

    fun doLanding() {
        val now = System.currentTimeMillis()
        flights.firstOrNull { it.lt == null }?.let { f ->
            val i = flights.indexOfFirst { it.t == f.t }
            if (i >= 0) flights[i] = flights[i].copy(lt = now)
        }
        saveFlights()
        syncFlyingState()
        refreshPreviewNow()
    }

    /** Closes a stuck active flight with zero duration (landing = takeoff). */
    fun forceResetActive() {
        flights.firstOrNull { it.lt == null }?.let { f ->
            updateFlight(f.t) { it.copy(lt = f.t) }
            saveFlights()
            syncFlyingState()
        }
        emit(Op.ForceReset())
    }

    fun addFlight() {
        val (from, to) = recWindow()
        val now = System.currentTimeMillis()
        flights.add(Flight(if (now in from until to) now else from))
        saveFlights()
        afterData()
        emit(Op.AddFlight())
    }

    fun deleteFlight(t: Long) {
        flights.removeAll { it.t == t }
        if (curRecT == t) curRecT = null
        if (activeRecT == t) {
            activeRecT = null
            if (flying) {
                flying = false
                timerJob?.cancel()
                timerJob = null
                clockLive = false
            }
        }
        saveFlights()
        afterData()
        emit(Op.DeleteFlight(t))
    }

    /** «Не зберігати виліт»: the takeoff was pressed by mistake / too early — drop the active flight entirely. */
    fun cancelActiveFlight() {
        val f = flights.firstOrNull { it.lt == null } ?: return
        deleteFlight(f.t)
        FlightBridge.onStateChanged?.invoke()
        setStatus("Виліт скасовано — у звіти не потрапить", "ok")
    }

    fun updateFlight(t: Long, transform: (Flight) -> Flight) {
        val i = flights.indexOfFirst { it.t == t }
        if (i >= 0) flights[i] = transform(flights[i])
    }

    fun setFlightComment(t: Long, c: String) {
        val i = flights.indexOfFirst { it.t == t }
        if (i >= 0) flights[i] = flights[i].copy(c = c)
        saveFlights()
        afterData()
        emit(Op.SetFlightComment(t, c))
    }

    /** Sets (or clears, when [value] is empty) a custom field value on a flight. */
    fun setFlightField(t: Long, fieldId: String, value: String) {
        updateFlight(t) { f ->
            val x = LinkedHashMap(f.x)
            if (value.isEmpty()) x.remove(fieldId) else x[fieldId] = value
            f.copy(x = x)
        }
        saveFlights()
        afterData()
        emit(Op.SetFlightField(t, fieldId, value))
    }

    // TODO(decomp): inlined into the remote-op handler (and UI) in the APK.
    fun setFlightTakeoff(t: Long, hhmm: String) {
        updateFlight(t) { it.copy(t = TimeFmt.tsFromTime(it.t, hhmm)) }
        saveFlights()
        afterData()
        emit(Op.SetFlightTakeoff(t, hhmm))
    }

    /** Edits the landing time; empty [hhmm] re-opens the flight. Past-midnight wraps a day. */
    // TODO(decomp): inlined into the remote-op handler (and UI) in the APK.
    fun setFlightLanding(t: Long, hhmm: String?) {
        updateFlight(t) { f ->
            if (hhmm.isNullOrEmpty()) {
                f.copy(lt = null)
            } else {
                var lt = TimeFmt.tsFromTime(f.t, hhmm)
                if (lt < f.t) lt += DAY
                f.copy(lt = lt)
            }
        }
        saveFlights()
        afterData()
        syncFlyingState()
        emit(Op.SetFlightLanding(t, hhmm))
    }

    // TODO(decomp): inlined; local edits emit nothing (only remote-op path seen).
    fun setFlightRep(t: Long, rep: String?) {
        val i = flights.indexOfFirst { it.t == t }
        if (i >= 0) flights[i] = flights[i].copy(rep = rep)
        saveFlights()
        emit(Op.SetFlightRep(t, rep))
    }

    fun insertTemplate(text: String) {
        val rec = activeRec() ?: flights.lastOrNull()
        if (rec == null) {
            setStatus("Спершу «Взліт»", "err")
            return
        }
        val trimmed = text.trim()
        updateFlight(rec.t) { it.copy(c = Coords.addTemplateLine(System.currentTimeMillis(), it.c, text)) }
        saveFlights()
        if (hasActiveFlight() && Coords.isLand(trimmed)) {
            doLanding()
            setStatus("Виліт завершено: $trimmed", "ok")
        }
        afterData()
        emit(Op.InsertTemplate(text))
    }

    /** Coordinates from the floating panel: to the last text line of the flight, else to the flight itself. */
    fun attachCoordsToLast(mgrs: String) {
        val rec = activeRec() ?: flights.lastOrNull()
        if (rec == null) { setStatus("Немає вильоту — спершу «Взліт»", "err"); return }
        val lines = rec.c.split("\n")
        val i = lines.indexOfLast { Coords.TIME_RE.replace(it.trim(), "").let { t -> t.isNotEmpty() && !Coords.isCoord(t) } }
        if (i >= 0) { attachCoordsToLine(mgrs, i); setStatus("✓ Координати підставлено: $mgrs", "ok") } else attachCoordsToFlight(mgrs)
    }

    fun attachCoordsToFlight(mgrs: String) {
        val rec = activeRec() ?: flights.lastOrNull()
        if (rec == null) {
            setStatus("Немає вильоту — спершу «Взліт»", "err")
            return
        }
        val i = flights.indexOfFirst { it.t == rec.t }
        if (i >= 0) flights[i] = flights[i].copy(mgrs = mgrs)
        saveFlights()
        afterData()
        setStatus("✓ Координати у звіт: $mgrs", "ok")
        emit(Op.AttachCoordsToFlight(mgrs))
    }

    /** Replaces any "за координатами …" tail of comment line [lineIdx] with [mgrs]. */
    fun attachCoordsToLine(mgrs: String, lineIdx: Int) {
        val rec = activeRec() ?: flights.lastOrNull() ?: return
        val lines = rec.c.split("\n")
        if (lineIdx < 0 || lineIdx >= lines.size) return
        updateFlight(rec.t) { it.copy(c = Coords.withCoords(lines, lineIdx, mgrs).joinToString("\n")) }
        saveFlights()
        afterData()
        emit(Op.AttachCoordsToLine(mgrs, lineIdx))
    }

    fun resetShift() {
        flights.clear()
        activeRecT = null
        curRecT = null
        if (flying) {
            flying = false
            timerJob?.cancel()
            timerJob = null
            clockLive = false
            clock = "00:00"
        }
        saveFlights()
        afterData()
        setStatus("✓ Зміну скинуто", "ok")
        emit(Op.ResetShift())
    }

    /** Derives flying/timer state from the flight list and (re)starts the clock ticker. */
    fun syncFlyingState() {
        val active = flights.firstOrNull { it.lt == null }
        if (active != null) {
            val t = active.t
            activeRecT = t
            if (!flying || startTs != t) {
                flying = true
                startTs = t
                takeoffStr = TimeFmt.clockAt(t)
                landingStr = ""
                lastFlightMs = 0L
                clockLive = true
                timerJob?.cancel()
                timerJob = null
                timerJob = viewModelScope.launch {
                    while (flying) {
                        renderClock()
                        delay(250)
                    }
                }
            }
            renderClock()
            showInfo()
        } else {
            if (flying) {
                flying = false
                timerJob?.cancel()
                timerJob = null
                clockLive = false
                landingStr = TimeFmt.clockAt(System.currentTimeMillis())
            }
            flights.lastOrNull { it.lt != null }?.let { f ->
                lastFlightMs = (f.lt ?: f.t) - f.t
                clock = TimeFmt.fmt(lastFlightMs)
            }
            showInfo()
        }
        FlightBridge.onStateChanged?.invoke()
    }

    fun renderClock() {
        clock = TimeFmt.fmt(System.currentTimeMillis() - startTs)
        if (showAir && mode == "current" && !previewEdited && previewVisible) updatePreview(false)
    }

    fun showInfo() {
        val lines = buildList {
            if (takeoffStr.isNotEmpty()) add("Взліт: $takeoffStr")
            if (landingStr.isNotEmpty()) add("Сів: $landingStr")
            if (lastFlightMs > 0) add("У польоті: " + TimeFmt.fmt(lastFlightMs))
        }
        flightInfo = if (lines.isNotEmpty()) lines.joinToString("\n") else "таймер зупинено"
    }

    // ==== loss of aircraft ====================================================================

    /** Marks the active (or latest) flight as lost with [cause] and opens the loss draft. */
    fun reportLoss(at: Long, cause: String) {
        val rec = activeRec() ?: flights.maxByOrNull { it.t }
        if (rec == null) {
            setStatus("Немає вильоту — спершу «Взліт»", "err")
            return
        }
        val origComment = rec.c
        val line = TimeFmt.hm(at) + " " + Loss.line(cause)
        val prev = origComment.trimEnd()
        setFlightComment(rec.t, if (prev.isEmpty()) line else prev + "\n" + line)
        if (hasActiveFlight()) landing(false)
        // Loss is counted in day/night reports by its own time (ReportBuilder.lossesIn), not via dayCfg.lost.

        val coords = rec.mgrs.ifEmpty {
            origComment.split("\n")
                .map { Coords.TIME_RE.replace(it.trim(), "") }
                .lastOrNull { Coords.isCoord(it) } ?: ""
        }
        val pilot = (store.sp.getString("kap_loss_pilot", "") ?: "").ifEmpty { leader }
        val drone = store.sp.getString("kap_loss_drone", "") ?: ""
        val surname = store.sp.getString("kap_loss_surname", "") ?: ""
        val model = drone.ifEmpty { droneModel }
        // lost drone leaves the position: a "lost" entry in the drone list (hidden from the tabs)
        val lostT = maxOf(at, (droneIns.maxOfOrNull { it.t } ?: 0L) + 1)
        droneIns.add(DroneIn(t = lostT, model = model, kind = DRONE_LOST))
        saveDroneIns()
        lossDraft = LossDraft(rec.t, cause, at, coords, pilot, model, surname = surname, lostT = lostT)
        setStatus("Втрату борту відмічено о " + TimeFmt.hm(at) + " · піде у звіт «День/Ніч» за часом втрати · на позиції −1", "err")
    }

    /** Updates the loss draft; pilot/drone are remembered for next time. */
    fun updateLoss(
        coords: String? = null, pilot: String? = null, drone: String? = null,
        tail: String? = null, serial: String? = null, surname: String? = null,
    ) {
        val d = lossDraft ?: return
        if (pilot != null) store.putString("kap_loss_pilot", pilot)
        if (drone != null) store.putString("kap_loss_drone", drone)
        if (surname != null) store.putString("kap_loss_surname", surname)
        lossDraft = d.copy(
            coords = coords ?: d.coords, pilot = pilot ?: d.pilot, drone = drone ?: d.drone,
            tail = tail?.filter { it.isDigit() }?.take(4) ?: d.tail, serial = serial ?: d.serial, surname = surname ?: d.surname,
        )
        // keep the "lost" list entry in step with the loss screen (model / 4 digits / number)
        val nd = lossDraft ?: return
        val li = droneIns.indexOfFirst { it.t == nd.lostT && it.kind == DRONE_LOST }
        if (li >= 0 && (drone != null || tail != null || serial != null)) {
            droneIns[li] = droneIns[li].copy(model = nd.drone.trim(), tail = nd.tail, code = nd.serial.trim())
            saveDroneIns()
        }
    }

    /**
     * «Дронів на позиції» from the drone list: every «Заїхав» entry minus «Переданий» and lost
     * ones (matched by number, else 4 digits, else model). Grouped by model.
     */
    /** For reports: from the list once it has any «Заїхав» entry, else the manual editor rows. */
    fun reportPosDrones(): List<NamedQty>? =
        if (droneIns.any { it.kind == DRONE_IN }) positionDrones() else null

    fun positionDrones(): List<NamedQty> {
        dataRev
        val ins = droneIns.filter { it.kind == DRONE_IN }.toMutableList()
        val minus = droneIns.filter { it.kind == DRONE_OUT || it.kind == DRONE_LOST }.sortedBy { it.t }
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
        return ins.groupBy { it.model.trim() }.map { (k, v) -> NamedQty(k, v.size) }
    }

    /** Flight + comment-line index of the "Втрата дрону" line (to attach map coordinates). */
    fun lossLineTarget(): Pair<Flight, Int>? {
        val draft = lossDraft
        val f = draft?.let { d -> flights.firstOrNull { it.t == d.flightT } }
            ?: flights.lastOrNull { it.c.contains("Втрата дрону") }
            ?: return null
        val idx = f.c.split("\n").indexOfLast { it.contains("Втрата дрону") }
        return if (idx >= 0) Pair(f, idx) else null
    }

    fun lossMessage(): String {
        val d = lossDraft ?: return ""
        return buildString {
            append(TimeFmt.fmtD(d.at)).append(' ').append(TimeFmt.hm(d.at))
            if (name.isNotBlank()) append(", СП \"").append(name.trim()).append('"')
            append(".\n")
            append(Loss.line(d.cause)).append(".")
            // drone right after the cause
            append("\nДрон: ").append(d.drone.trim())
            if (d.tail.isNotBlank() || d.serial.isNotBlank()) append(" (").append(d.tail.ifEmpty { "    " }).append(") ").append(d.serial.trim())
            append("\nОрієнтовні координати: ")
            append(d.coords.trim().ifEmpty { "—" })
            append("\nПілот: ").append(d.pilot.trim())
            if (d.surname.isNotBlank()) append("\nПрізвище пілота: ").append(d.surname.trim())
        }
    }

    // ==== templates / events / fields / points ===============================================

    /** Adds comma/newline separated templates, skipping duplicates. Returns how many were added. */
    fun addTemplates(raw: String): Int {
        var added = 0
        raw.split(Regex("[\n,]+")).map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            if (!templates.contains(it)) {
                templates.add(it)
                added++
            }
        }
        if (added > 0) saveTemplates()
        emit(Op.AddTemplates(raw))
        return added
    }

    // TODO(decomp): inlined into the remote-op handler (and UI) in the APK.
    fun removeTemplate(index: Int) {
        if (index >= 0 && index < templates.size) {
            templates.removeAt(index)
            saveTemplates()
            emit(Op.RemoveTemplate(index))
        }
    }

    /** New event in the current shift, carrying the picked map coordinates. */
    fun addEvent(picked: String) {
        val (from, to) = ReportBuilder.shiftBounds(System.currentTimeMillis(), shiftStart)
        val now = System.currentTimeMillis()
        events.add(Event(if (now in from until to) now else from, null, "", picked))
        saveEvents()
        afterData()
        emit(Op.AddEvent(picked))
    }

    // ==== map routes ========================================================================

    fun routeAdd(pts: List<Pair<Double, Double>>) {
        if (pts.isEmpty()) return
        routeStrokes.add(route.size); route.addAll(pts)
    }

    fun routeUndo() {
        val from = routeStrokes.removeLastOrNull() ?: return
        while (route.size > from) route.removeAt(route.lastIndex)
    }

    fun routeClear() { route.clear(); routeStrokes.clear(); routeEditId = null }

    private fun saveRoutes(sync: Boolean = true) {
        store.putStringNow("kap_routes", store.json.encodeToString(ListSerializer(Route.serializer()), routes.toList()))
        if (sync) emit(Op.SetRoutes(routes.toList()))
    }

    /** Remote route list: replaces ours, keeping this device's show/hide choice for known routes. */
    private fun applyRemoteRoutes(remote: List<Route>) {
        val vis = routes.associate { it.id to it.visible }
        val next = remote.map { r -> r.copy(visible = vis[r.id] ?: true) }
        if (next == routes.toList()) return
        routes.clear(); routes.addAll(next)
        saveRoutes(sync = false)
    }

    private fun applyRemoteDrones(remote: List<DroneIn>) {
        if (remote == droneIns.toList()) return
        droneIns.clear(); droneIns.addAll(remote)
        saveDroneIns(sync = false)
    }

    /** Saves the route being drawn (new or the one loaded for editing) and clears the drawing. */
    fun saveRoute(name: String, color: String) {
        if (route.size < (if (drawClosed) 3 else 2)) return
        val nm = name.trim().ifEmpty { (if (drawClosed) "Область " else "Маршрут ") + (routes.size + 1) }
        val i = routes.indexOfFirst { it.id == routeEditId }
        val r = Route(routeEditId ?: ("r" + System.currentTimeMillis().toString(36)), nm, color, route.toList(), true, drawClosed)
        if (i >= 0) routes[i] = r else routes.add(r)
        saveRoutes(); routeClear()
        setStatus((if (r.closed) "✓ Область «" else "✓ Маршрут «") + nm + "» збережено", "ok")
    }

    fun updateRoute(id: String, name: String? = null, color: String? = null) {
        val i = routes.indexOfFirst { it.id == id }; if (i < 0) return
        routes[i] = routes[i].copy(name = name?.trim()?.ifEmpty { null } ?: routes[i].name, color = color ?: routes[i].color)
        saveRoutes()
    }

    fun setRouteVisible(id: String, on: Boolean) {
        val i = routes.indexOfFirst { it.id == id }; if (i < 0) return
        routes[i] = routes[i].copy(visible = on); saveRoutes(sync = false)
    }

    fun setAllRoutesVisible(on: Boolean) {
        for (i in routes.indices) routes[i] = routes[i].copy(visible = on)
        saveRoutes(sync = false)
    }

    fun deleteRoute(id: String) { routes.removeAll { it.id == id }; saveRoutes() }

    /** Loads a saved route into the drawing for editing (save overwrites it). */
    fun editRoute(id: String) {
        val r = routes.firstOrNull { it.id == id } ?: return
        routeClear(); routeAdd(r.pts); routeEditId = id; drawClosed = r.closed
    }

    // ==== «Ураження»: separate report ======================================================

    fun updateStrike(transform: (StrikeDraft) -> StrikeDraft) {
        strike = transform(strike)
        store.putString("kap_strike", store.json.encodeToString(StrikeDraft.serializer(), strike))
    }

    /** New strike: time = now, coords/БК/стрім cleared; crew, position, target, pilot kept. */
    fun newStrike() = updateStrike {
        it.copy(t = System.currentTimeMillis(), coords = "", bk = "", stream = "", result = "Ціль уражено.")
    }

    fun strikeText(): String {
        val s = strike
        val t = if (s.t > 0) s.t else System.currentTimeMillis()
        val crew = s.crew.ifBlank { name }.trim().uppercase()
        val pos = s.pos.ifBlank { name }.trim().uppercase()
        val bk = s.bk.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        out += TimeFmt.fmtD4(t) + ".  " + TimeFmt.hm(t)
        out += "Екіпаж - $crew"
        out += "Позиція - $pos"
        out += "Ціль : -  " + s.target.trim()
        if (s.coords.isNotBlank()) out += s.coords.trim()
        out += "БК - "
        if (bk.isEmpty()) out += "1. " else bk.forEachIndexed { i, b -> out += "${i + 1}. $b" }
        out += "Стрім - " + s.stream.trim()
        out += "Пілот - " + s.pilot.ifBlank { leader }.trim()
        out += s.result.trim()
        return out.joinToString("\n")
    }

    // ==== «Заїхав / Переданий дрон»: separate local lists + own reports =====================

    /** Entries of a tab (cleared ones are hidden but still count for «Дронів на позиції»). */
    fun dronesOf(kind: String): List<IndexedValue<DroneIn>> =
        droneIns.withIndex().filter { it.value.kind == kind && !it.value.cleared }

    /** Adds (index = -1) or replaces an entry; returns its 1-based number within its list. */
    fun saveDrone(index: Int, kind: String, model: String, tail: String, code: String): Int {
        val m = model.trim().ifEmpty { "Matrice 4T" }
        val d = DroneIn(
            t = droneIns.getOrNull(index)?.t ?: System.currentTimeMillis(),
            code = code.replace(Regex("\\s+"), " ").trim().take(80),
            model = m,
            tail = tail.filter { it.isDigit() }.take(4),
            kind = kind,
            pos = droneIns.getOrNull(index)?.pos ?: false,
        )
        val before = positionDrones().sumOf { it.qty }
        val prev = droneIns.getOrNull(index)
        if (index in droneIns.indices) droneIns[index] = d else droneIns.add(d)
        droneModel = m
        store.putString("kap_drone_model", m)
        saveDroneIns()
        val after = positionDrones().sumOf { it.qty }
        val pos = if (after != before) " · на позиції: $before → $after" else ""
        val list = dronesOf(kind)
        val n = list.indexOfFirst { it.value === d } + 1
        val dup = d.code.isNotEmpty() && list.count { it.value.code == d.code } > 1
        val what = if (kind == "out") "Переданий дрон" else "Заїхав дрон"
        setStatus((if (dup) "⚠ $what №$n — цей номер уже є у списку" else "✓ $what №$n") + pos, if (dup) "err" else "ok")
        return n
    }

    /** «На позиції» mark (just a note, synced, not in reports). */
    fun toggleDronePos(index: Int) {
        val d = droneIns.getOrNull(index) ?: return
        droneIns[index] = d.copy(pos = !d.pos)
        saveDroneIns()
    }

    fun removeDroneEntry(index: Int) {
        if (index !in droneIns.indices) return
        droneIns.removeAt(index); saveDroneIns()
    }

    /**
     * «Очистити»: the tab's list/report starts from №1 again; the drones still count for
     * «Дронів на позиції». [full] also drops them from the position count.
     */
    fun clearDrones(kind: String, full: Boolean = false) {
        if (full) droneIns.removeAll { it.kind == kind }
        else for (i in droneIns.indices) if (droneIns[i].kind == kind) droneIns[i] = droneIns[i].copy(cleared = true)
        saveDroneIns()
    }

    /** Resets the position count: drops every entry (incl. cleared / lost). */
    fun resetPositionDrones() { droneIns.clear(); saveDroneIns() }

    private fun saveDroneIns(sync: Boolean = true) {
        store.putStringNow("kap_dronein", store.json.encodeToString(ListSerializer(DroneIn.serializer()), droneIns.toList()))
        if (sync) emit(Op.SetDrones(droneIns.toList()))
    }

    fun droneReportText(kind: String): String {
        val list = dronesOf(kind).map { it.value }
        if (list.isEmpty()) return ""
        val one = list.size == 1
        val title = if (kind == "out") (if (one) "Переданий дрон" else "Передані дрони")
                    else (if (one) "Заїхав дрон" else "Заїхали дрони")
        val head = title + (if (name.isNotBlank()) " · СП \"$name\"" else "") + " · " + TimeFmt.fmtD(System.currentTimeMillis())
        val rows = list.mapIndexed { i, d -> "${i + 1}. ${d.model} (${d.tail.ifEmpty { "    " }}) ${d.code}".trimEnd() }
        return (listOf(head) + rows + "Всього: ${list.size}").joinToString("\n")
    }

    fun updateEvent(index: Int, transform: (Event) -> Event) {
        if (index < 0 || index >= events.size) return
        events[index] = transform(events[index])
        saveEvents()
        afterData()
        val e = events[index]
        emit(Op.UpdateEvent(index, e.t, e.t2, e.text, e.mgrs))
    }

    fun removeEvent(index: Int) {
        if (index < 0 || index >= events.size) return
        events.removeAt(index)
        saveEvents()
        afterData()
        emit(Op.RemoveEvent(index))
    }

    fun addField(name: String, type: String, opts: List<String>) {
        val id = "f" + System.currentTimeMillis().toString(36) + Random.nextInt(0..9999).toString(36)
        fields.add(Field(id, name, type, opts))
        saveFields()
        afterData()
        emit(Op.AddField(name, type, opts))
    }

    fun removeField(index: Int) {
        if (index < 0 || index >= fields.size) return
        fields.removeAt(index)
        saveFields()
        afterData()
        emit(Op.RemoveField(index))
    }

    fun addPoint(lat: Double, lng: Double): Point {
        val mgrs = try {
            Coords.fmtMGRS(Mgrs.forward(lng, lat))
        } catch (e: Exception) {
            String.format("%.5f, %.5f", lat, lng)
        }
        val id = "p" + System.currentTimeMillis().toString(36) + Random.nextInt(0..999).toString(36)
        val p = Point(id, lat, lng, "", "#c0392b", mgrs)
        points.add(p)
        savePoints()
        emit(Op.AddPoint(lat, lng))
        return p
    }

    fun updatePoint(id: String, name: String?, color: String?) {
        val i = points.indexOfFirst { it.id == id }
        if (i < 0) return
        points[i] = points[i].copy(name = name ?: points[i].name, color = color ?: points[i].color)
        savePoints()
        emit(Op.UpdatePoint(id, name, color))
    }

    // TODO(decomp): inlined into the remote-op handler (and UI) in the APK.
    fun removePoint(id: String) {
        points.removeAll { it.id == id }
        savePoints()
        emit(Op.RemovePoint(id))
    }

    // TODO(decomp): inlined into the remote-op handler (and UI) in the APK.
    fun clearPoints() {
        points.clear()
        savePoints()
        setStatus("Точки очищено", "ok")
        emit(Op.ClearPoints())
    }

    // ==== day config / header fields ==========================================================

    fun setDayLost(n: Int) {
        dayCfg = dayCfg.copy(lost = n)
        saveDayCfg()
        syncPreview()
        emitDayCfg()
    }

    // TODO(decomp): inlined at UI call site.
    fun setDayDmg(n: Int) {
        dayCfg = dayCfg.copy(dmg = n)
        saveDayCfg()
        syncPreview()
        emitDayCfg()
    }

    fun updateProp(index: Int, name: String?, qty: Int?) {
        val list = dayCfg.props.toMutableList()
        if (index < 0 || index >= list.size) return
        list[index] = NamedQty(name ?: list[index].name, qty ?: list[index].qty)
        dayCfg = dayCfg.copy(props = list)
        saveDayCfg()
        syncPreview()
        emitDayCfg()
    }

    fun updateDrone(index: Int, name: String?, qty: Int?) {
        val list = dayCfg.drones.toMutableList()
        if (index < 0 || index >= list.size) return
        list[index] = NamedQty(name ?: list[index].name, qty ?: list[index].qty)
        dayCfg = dayCfg.copy(drones = list)
        saveDayCfg()
        syncPreview()
        emitDayCfg()
    }

    // TODO(decomp): inlined at UI call site.
    fun addProp() {
        dayCfg = dayCfg.copy(props = ArrayList(dayCfg.props + NamedQty()))
        saveDayCfg()
        emitDayCfg()
    }

    // TODO(decomp): inlined at UI call site.
    fun addDrone() {
        dayCfg = dayCfg.copy(drones = ArrayList(dayCfg.drones + NamedQty()))
        saveDayCfg()
        emitDayCfg()
    }

    // TODO(decomp): inlined at UI call site (EditorKt lambda 36, cases 2/3).
    fun removeProp(index: Int) {
        val list = dayCfg.props.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        dayCfg = dayCfg.copy(props = list); saveDayCfg(); syncPreview(); emitDayCfg()
    }

    // TODO(decomp): inlined at UI call site (EditorKt lambda 36, cases 2/3).
    fun removeDrone(index: Int) {
        val list = dayCfg.drones.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        dayCfg = dayCfg.copy(drones = list); saveDayCfg(); syncPreview(); emitDayCfg()
    }

    fun emitDayCfg() = emit(Op.SetDayCfg(dayCfg))

    fun changeName(v: String) {
        name = v
        store.putStringNow("kap_name", v)
        afterData()
        emit(Op.SetName(v))
    }

    fun changeLeader(v: String) {
        leader = v
        store.putStringNow("kap_leader", v)
        afterData()
        emit(Op.SetLeader(v))
    }

    fun changeShiftStart(v: String) {
        shiftStart = v
        store.putString("kap_shiftstart", v)
        syncPreview()
        emit(Op.SetShiftStart(v))
    }

    fun changeDayStart(v: String) {
        dayStart = v
        store.putString("kap_daystart", v)
        syncPreview()
        emit(Op.SetDayStart(v))
    }

    fun changeDayEnd(v: String) {
        dayEnd = v
        store.putString("kap_dayend", v)
        syncPreview()
        emit(Op.SetDayEnd(v))
    }

    fun changeShiftCode(v: String) {
        shiftCode = v
        store.putString("kap_shiftcode", v)
        syncPreview()
        emit(Op.SetShiftCode(v))
    }

    fun changeSortieCount(v: String) {
        sortieCount = v
        store.putString("kap_sorties", v)
        syncPreview()
        emit(Op.SetSortieCount(v))
    }

    // ==== settings / toggles (all inlined at their UI call sites in the APK) ==================

    fun changeScoutAuto(v: Boolean) {
        scoutAuto = v
        store.putBool("kap_scout", v)
        syncPreview()
        emit(Op.SetScoutAuto(v))
    }

    fun changeMode(v: String) {
        val changed = mode != v
        mode = v
        store.putString("kap_reportmode", v)
        if (changed) { previewEdited = false; bump(); updatePreview(true) } else afterData()
    }

    fun changeThemeMode(v: String) {
        themeMode = v
        store.putString("kap_theme", v)
    }

    fun changeShowAir(v: Boolean) {
        showAir = v
        store.putBool("kap_showair", v)
        syncPreview()
    }

    fun changeBigEdit(v: Boolean) {
        bigEdit = v
        store.putBool("kap_bigedit", v)
    }

    fun changeShowCountShift(v: Boolean) {
        showCountShift = v
        store.putBool("kap_countshift", v)
        syncPreview()
    }

    fun changeShiftDetailed(v: Boolean) {
        shiftDetailed = v
        store.putBool("kap_shiftdetail", v)
        syncPreview()
    }

    fun changeAutoSend(v: Boolean) {
        autoSend = v
        store.putBool("kap_autosend", v)
    }

    fun changeAutoWhatsApp(v: Boolean) {
        autoWhatsApp = v
        store.putBool("kap_wa", v)
    }

    fun changeWaPhone(v: String) {
        waPhone = v
        store.putString("kap_waphone", v)
    }

    fun changeAutoSignal(v: Boolean) {
        autoSignal = v
        store.putBool("kap_signal", v)
    }

    fun changeNeedsText(v: String) {
        needsText = v
        store.putString("kap_needs", v)
    }

    fun toggleNeedsNumbered() {
        needsNumbered = !needsNumbered
        store.putBool("kap_needs_num", needsNumbered)
    }

    fun toggleFlight() {
        if (hasActiveFlight()) landing(true) else takeoff()
    }

    fun togglePreview() {
        previewVisible = !previewVisible
        if (previewVisible) {
            previewEdited = false
            updatePreview(true)
        }
    }

    /** Manual edit of the preview text; in "current" mode it is saved onto the flight. */
    fun editPreview(text: String) {
        previewText = text
        previewEdited = true
        if (mode == "current") {
            val rec = curRec() ?: return
            if (rec.t !in repBase) repBase[rec.t] = previewBase.ifEmpty { buildReport(stripRep = true) }
            val i = flights.indexOfFirst { it.t == rec.t }
            if (i >= 0) flights[i] = flights[i].copy(rep = text)
            saveFlights()
        }
    }

    /** Drops manual edits (saved per-flight report or preview edit). */
    fun resetReport() {
        val rec = curRec()
        if (mode != "current" || rec == null || rec.rep.isNullOrEmpty()) {
            updatePreview(true)
            setStatus("Повернуто автоматичний звіт", "ok")
        } else {
            clearSavedReport(rec.t)
        }
    }

    /** Shows the report of flight [t] in the preview. */
    fun selectRec(t: Long) {
        curRecT = t
        previewEdited = false
        updatePreview(true)
        if (!previewVisible) previewVisible = true
    }

    fun sendDirect() {
        directTrigger++
    }

    fun stopRing() {
        ringing = false
    }

    fun dismissLoss() {
        lossDraft = null
    }

    // ==== alarms / schedules ==================================================================

    fun setAlarmIn(minutes: Int) {
        val at = minutes * 60_000L + System.currentTimeMillis()
        alarmAt = at
        store.sp.edit().putLong("kap_alarm", alarmAt).apply()
        setStatus("⏰ Будильник о " + TimeFmt.hm(at), "ok")
    }

    fun cancelAlarm() {
        alarmAt = 0L
        store.sp.edit().putLong("kap_alarm", alarmAt).apply()
    }

    /** Next enabled daily alarm timestamp, or 0. */
    fun nextDaily(): Long {
        val now = System.currentTimeMillis()
        var next = 0L
        for (d in daily) {
            if (!d.on) continue
            var ts = TimeFmt.tsFromTime(now, d.t)
            if (ts <= now) ts += DAY
            if (next == 0L || ts < next) next = ts
        }
        return next
    }

    fun setDailyTime(index: Int, t: String) {
        if (!Regex("^\\d{1,2}:\\d{2}$").matches(t)) return
        if (index < 0 || index >= daily.size) return
        daily[index] = daily[index].copy(t = t)
        saveDaily()
    }

    fun setDailyOn(index: Int, on: Boolean) {
        if (index < 0 || index >= daily.size) return
        daily[index] = daily[index].copy(on = on)
        saveDaily()
    }

    fun updateSchedule(id: String, transform: (ScheduledSend) -> ScheduledSend) {
        val i = schedules.indexOfFirst { it.id == id }
        if (i < 0) return
        val was = schedules[i].enabled
        schedules[i] = transform(schedules[i])
        saveSchedules()
        if (!was && schedules[i].enabled) ReportAlarmReceiver.ensureExactAllowed(getApplication<Application>())
        try {
            ReportAlarmReceiver.rescheduleAll(getApplication<Application>())
        } catch (_: Throwable) {
        }
    }

    /** Opens the target messenger now with the report the schedule would send. */
    fun testSchedule(s: ScheduledSend) {
        scheduleTest = s
        scheduleTestTrigger++
    }

    // ==== persistence =========================================================================

    /** Persists flights, dropping those older than KEEP_DAYS. */
    fun saveFlights() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * DAY
        val kept = flights.filter { it.t >= cutoff }
        if (kept.size != flights.size) {
            flights.clear()
            flights.addAll(kept)
        }
        trackFlightChanges(cutoff)
        store.putStringNow("kap_flights", store.json.encodeToString(flightsSer, flights.toList()))
        bump()
    }

    /**
     * Stamps changed flights with u = now, records deletions, and on a client sends every
     * changed flight to the hub as a whole (UpsertFlight) — so edits from any device converge.
     */
    private fun trackFlightChanges(cutoff: Long) {
        val now = System.currentTimeMillis()
        val changed = ArrayList<Flight>()
        for (i in flights.indices) {
            val f = flights[i]
            val old = lastSaved[f.t]
            if (old == null || old != f.copy(u = 0L)) {
                if (!adoptingRemote) flights[i] = f.copy(u = maxOf(now, f.u))
                changed += flights[i]
            }
        }
        val ids = flights.map { it.t }.toSet()
        val gone = lastSaved.keys.filter { it !in ids && it >= cutoff }
        if (!adoptingRemote) for (t in gone) deletedT[t] = now
        if (gone.isNotEmpty() || deletedT.keys.any { it < cutoff }) {
            deletedT.keys.removeAll { it < cutoff }
            store.putString("kap_flight_del", store.json.encodeToString(
                kotlinx.serialization.builtins.MapSerializer(Long.serializer(), Long.serializer()), deletedT.toMap()))
        }
        lastSaved = flights.associate { it.t to it.copy(u = 0L) }
        if (netMode == NetMode.CLIENT && !applyingRemote && !adoptingRemote) {
            for (t in gone) onSendOp?.invoke(Op.DeleteFlight(t))
            for (f in changed) onSendOp?.invoke(Op.UpsertFlight(f))
        }
    }

    /** Hub: a client's whole flight replaces ours; a second open flight is refused (busy). */
    private fun applyUpsert(f0: Flight) {
        val f = normFlight(f0)
        if (deletedT.containsKey(f.t)) return
        val i = flights.indexOfFirst { it.t == f.t }
        if (i < 0) {
            if (f.lt == null && flights.any { it.lt == null }) return
            val pos = flights.indexOfFirst { it.t > f.t }.let { if (it < 0) flights.size else it }
            flights.add(pos, f)
        } else {
            if (f == flights[i]) return
            flights[i] = f
        }
        adoptingRemote = true
        try { saveFlights() } finally { adoptingRemote = false }
        activeRecT = flights.firstOrNull { it.lt == null }?.t
        syncFlyingState()
        afterData()
    }

    /** Persists events, dropping those older than KEEP_DAYS. */
    fun saveEvents() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * DAY
        val kept = events.filter { it.t >= cutoff }
        if (kept.size != events.size) {
            events.clear()
            events.addAll(kept)
        }
        store.putStringNow("kap_events", store.json.encodeToString(eventsSer, events.toList()))
        bump()
    }

    fun saveTemplates() {
        store.putStringNow("kap_templates", store.json.encodeToString(ListSerializer(String.serializer()), templates.toList()))
        bump()
    }

    fun saveFields() {
        store.putStringNow("kap_fields", store.json.encodeToString(ListSerializer(Field.serializer()), fields.toList()))
        bump()
    }

    fun savePoints() {
        store.putStringNow("kap_points", store.json.encodeToString(ListSerializer(Point.serializer()), points.toList()))
        bump()
    }

    fun saveDayCfg() {
        store.putStringNow("kap_daycfg", store.json.encodeToString(DayCfg.serializer(), dayCfg))
        bump()
    }

    fun saveDaily() {
        store.putString("kap_almdaily", store.json.encodeToString(ListSerializer(DailyAlarm.serializer()), daily.toList()))
    }

    private fun saveSchedules() {
        store.putString("kap_schedules", store.json.encodeToString(ListSerializer(ScheduledSend.serializer()), schedules.toList()))
    }

    // ==== network =============================================================================

    private fun emit(op: Op) {
        if (applyingRemote) return
        when (netMode) {
            NetMode.SOLO -> Unit
            NetMode.HUB -> onBroadcast?.invoke()
            NetMode.CLIENT -> if (!isFlightOp(op)) onSendOp?.invoke(op)
        }
    }

    /** Flight edits — a client sends them as whole flights (see trackFlightChanges). */
    private fun isFlightOp(op: Op) = when (op) {
        is Op.Takeoff, is Op.Landing, is Op.ForceReset, is Op.AddFlight, is Op.DeleteFlight,
        is Op.SetFlightTakeoff, is Op.SetFlightLanding, is Op.SetFlightComment, is Op.SetFlightField,
        is Op.SetFlightRep, is Op.InsertTemplate, is Op.AttachCoordsToFlight, is Op.AttachCoordsToLine -> true
        else -> false
    }

    /**
     * Starts the embedded sync hub on port 8777 and advertises it via NSD.
     * Reuses [code] if it normalises to ≥16 chars, otherwise generates a fresh pairing code.
     */
    fun startHub(code: String?) {
        netCode = code?.takeIf { NetUtil.normCode(it).length >= 16 }?.let { NetUtil.normCode(it) } ?: NetUtil.genCode()
        netMode = NetMode.HUB
        onBroadcast = { sync.broadcastSnapshot() }
        onSendOp = null
        // TODO(decomp): SyncManager.startHub (embeddedServer(CIO, port = 8777, host = "0.0.0.0")) is inlined here.
        sync.startHub(netCode)
        // TODO(decomp): NsdHelper.register is inlined here.
        nsd.register(hubServiceName(), HUB_PORT)
        store.putString("kap_netmode", "hub")
        store.putStringNow("kap_netcode", netCode)
    }

    /** @param deviceName TODO(decomp): default evaluated at every call site but unused in the APK. */
    fun connectToHub(ip: String, code: String, @Suppress("UNUSED_PARAMETER") deviceName: String = Build.MODEL) {
        netMode = NetMode.CLIENT
        // TODO(decomp): SyncManager.sendOp is inlined into this callback in the APK.
        onSendOp = { op -> sync.sendOp(op) }
        onBroadcast = null
        // TODO(decomp): SyncManager.connect (CIO HttpClient + WebSockets, reconnect loop) is inlined here.
        sync.connect(ip.trim(), NetUtil.normCode(code))
        store.putStringNow("kap_netmode", "client")
        store.putStringNow("kap_hubip", ip.trim())
        store.putStringNow("kap_hubcode", NetUtil.normCode(code))
    }

    fun stopNet() {
        netMode = NetMode.SOLO
        netStatus = ""
        netPeers = 0
        netCode = ""
        onSendOp = null
        onBroadcast = null
        sync.shutdown()
        nsd.stop()
        store.putStringNow("kap_netmode", "solo")
    }

    /**
     * Restores the persisted network role at app start.
     * TODO(decomp): inlined into a LaunchedEffect in App() in the APK.
     */
    fun restoreNet() {
        val sp = store.sp
        when (sp.getString("kap_netmode", "solo") ?: "solo") {
            "hub" -> startHub(sp.getString("kap_netcode", "") ?: "")
            "client" -> {
                val ip = sp.getString("kap_hubip", "") ?: ""
                val code = sp.getString("kap_hubcode", "") ?: ""
                if (ip.isNotEmpty() && code.isNotEmpty()) {
                    connectToHub(ip, code)
                } else if (code.isNotEmpty()) {
                    nsd.startDiscovery { found ->
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            if (netMode != NetMode.CLIENT) connectToHub(found.ip, code)
                        }
                    }
                } else {
                    netStatus = "Скануйте QR хаба: Синхронізація → «Сканувати QR хаба»"
                }
            }
        }
    }

    /**
     * Fills [foundHubs] with hubs discovered via NSD (deduplicated by ip:port).
     * TODO(decomp): inlined into a DisposableEffect of the sync screen in the APK.
     */
    fun discoverHubs() {
        foundHubs.clear()
        nsd.startDiscovery { found ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (foundHubs.none { it.ip == found.ip && it.port == found.port }) foundHubs.add(found)
            }
        }
    }

    /** Applies one op received from the network. */
    private fun applyRemoteOp(op: Op) {
        applyingRemote = true
        try {
            when (op) {
                is Op.Takeoff -> if (!hasActiveFlight()) doTakeoff(if (op.t > 0) op.t else System.currentTimeMillis())
                is Op.Landing -> doLanding()
                is Op.ForceReset -> forceResetActive()
                is Op.AddFlight -> addFlight()
                is Op.DeleteFlight -> deleteFlight(op.t)
                is Op.SetFlightTakeoff -> setFlightTakeoff(op.t, op.hhmm)
                is Op.SetFlightLanding -> setFlightLanding(op.t, op.hhmm)
                is Op.SetFlightComment -> setFlightComment(op.t, op.c)
                is Op.SetFlightField -> setFlightField(op.t, op.fieldId, op.value)
                is Op.InsertTemplate -> insertTemplate(op.text)
                is Op.SetFlightRep -> setFlightRep(op.t, op.rep)
                is Op.AddTemplates -> addTemplates(op.raw)
                is Op.RemoveTemplate -> removeTemplate(op.index)
                is Op.AddEvent -> addEvent(op.picked)
                is Op.UpdateEvent -> updateEvent(op.index) { e ->
                    Event(op.t ?: e.t, op.t2, op.text ?: e.text, op.mgrs ?: e.mgrs)
                }
                is Op.RemoveEvent -> removeEvent(op.index)
                is Op.AddField -> addField(op.name, op.type, op.opts)
                is Op.RemoveField -> removeField(op.index)
                is Op.SetDayCfg -> {
                    dayCfg = op.cfg
                    saveDayCfg()
                }
                is Op.AddPoint -> addPoint(op.lat, op.lng)
                is Op.UpdatePoint -> updatePoint(op.id, op.name, op.color)
                is Op.RemovePoint -> removePoint(op.id)
                is Op.ClearPoints -> clearPoints()
                is Op.AttachCoordsToFlight -> attachCoordsToFlight(op.mgrs)
                is Op.AttachCoordsToLine -> attachCoordsToLine(op.mgrs, op.lineIdx)
                is Op.SetName -> changeName(op.v)
                is Op.SetLeader -> changeLeader(op.v)
                is Op.SetShiftStart -> changeShiftStart(op.v)
                is Op.SetDayStart -> changeDayStart(op.v)
                is Op.SetDayEnd -> changeDayEnd(op.v)
                is Op.SetShiftCode -> changeShiftCode(op.v)
                is Op.SetSortieCount -> changeSortieCount(op.v)
                is Op.SetScoutAuto -> changeScoutAuto(op.v)
                is Op.ResetShift -> resetShift()
                is Op.UpsertFlight -> applyUpsert(op.f)
                is Op.SetRoutes -> applyRemoteRoutes(op.routes)
                is Op.SetDrones -> applyRemoteDrones(op.drones)
            }
        } finally {
            applyingRemote = false
        }
    }

    /** Client: replace local state with the hub's snapshot. */
    private fun applySnapshot(st: SharedState) {
        applyingRemote = true
        try {
            val resend = mergeSnapshotFlights(st)
            events.clear(); events.addAll(st.events)
            templates.clear(); templates.addAll(st.templates)
            fields.clear(); fields.addAll(st.fields)
            points.clear(); points.addAll(st.points)
            dayCfg = st.dayCfg
            name = st.name
            leader = st.leader
            shiftStart = st.shiftStart
            dayStart = st.dayStart
            dayEnd = st.dayEnd
            shiftCode = st.shiftCode
            sortieCount = st.sortieCount
            scoutAuto = st.scoutAuto
            if (st.syncV >= 2) { applyRemoteRoutes(st.routes); applyRemoteDrones(st.drones) }
            activeRecT = flights.firstOrNull { it.lt == null }?.t
            store.putStringNow("kap_flights", store.json.encodeToString(flightsSer, flights.toList()))
            bump()
            syncPreview()
            applyingRemote = false
            syncFlyingState()
            // our newer / not-yet-received edits go (again) to the hub
            for (f in resend) onSendOp?.invoke(Op.UpsertFlight(f))
        } finally {
            applyingRemote = false
        }
    }

    /**
     * Client: the hub is the master — its flights replace ours. Kept from ours (and re-sent):
     * a flight edited here while the link was down, if the hub hasn't changed it since; a flight
     * the hub doesn't have yet (taken off / added offline), unless the hub already has another
     * active one. So the local timer keeps running through a link loss.
     */
    private fun mergeSnapshotFlights(st: SharedState): List<Flight> {
        val now = System.currentTimeMillis()
        val remote = st.flights.map { normFlight(it) }
        val resend = ArrayList<Flight>()
        val out: List<Flight>
        if (st.syncV < 3) {
            out = remote // older hub: hub is authoritative
        } else {
            for (t in st.deleted) deletedT.putIfAbsent(t, now)
            val rMap = remote.filter { !deletedT.containsKey(it.t) }.associateBy { it.t }
            val merged = LinkedHashMap(rMap)
            val hubActive = rMap.values.any { it.lt == null }
            for (lf in flights) {
                if (deletedT.containsKey(lf.t)) continue
                val rf = rMap[lf.t]
                if (rf == null) {
                    if (lf.lt != null || !hubActive) { merged[lf.t] = lf; resend += lf }
                } else if (lf.u > rf.u && hubU[lf.t] == rf.u) {
                    merged[lf.t] = lf; resend += lf // offline edit, hub copy untouched
                }
            }
            out = merged.values.sortedBy { it.t }
            hubU.clear()
            for (f in rMap.values) hubU[f.t] = f.u
        }
        flights.clear()
        flights.addAll(out)
        adoptingRemote = true
        try { saveFlights() } finally { adoptingRemote = false }
        return resend
    }

    /**
     * Hub: merge a joining client's local state into ours (union by key; a remote landing
     * closes our still-open copy of the same flight; header fields only fill blanks).
     */
    private fun mergeState(st: SharedState) {
        applyingRemote = true
        try {
            val now = System.currentTimeMillis()
            for (t in st.deleted) deletedT.putIfAbsent(t, now)
            val fMap = LinkedHashMap(flights.associateBy { it.t })
            for (rf in st.flights) {
                if (deletedT.containsKey(rf.t)) continue
                val mine = fMap[rf.t]
                if (mine == null) {
                    fMap[rf.t] = normFlight(rf)
                } else if (mine.lt == null && rf.lt != null) {
                    fMap[rf.t] = mine.copy(lt = rf.lt)
                }
            }
            for (t in deletedT.keys) fMap.remove(t)
            flights.clear()
            flights.addAll(fMap.values.sortedBy { it.t })
            adoptingRemote = true
            try { saveFlights() } finally { adoptingRemote = false }

            for (t in st.templates) if (!templates.contains(t)) templates.add(t)

            val eMap = LinkedHashMap(events.associateBy { it.t })
            for (e in st.events) if (!eMap.containsKey(e.t)) eMap[e.t] = e
            events.clear()
            events.addAll(eMap.values.sortedBy { it.t })

            val pMap = LinkedHashMap(points.associateBy { it.id })
            for (p in st.points) if (!pMap.containsKey(p.id)) pMap[p.id] = p
            points.clear()
            points.addAll(pMap.values)

            val fdMap = LinkedHashMap(fields.associateBy { it.id })
            for (f in st.fields) if (!fdMap.containsKey(f.id)) fdMap[f.id] = f
            fields.clear()
            fields.addAll(fdMap.values)

            // routes / drones: union (client's own entries join the hub's list)
            val rIds = routes.map { it.id }.toSet()
            for (r in st.routes) if (r.id !in rIds) routes.add(r.copy(visible = true))
            saveRoutes(sync = false)
            val dKeys = droneIns.map { it.t to it.kind }.toSet()
            val newD = st.drones.filter { (it.t to it.kind) !in dKeys }
            if (newD.isNotEmpty()) {
                val all = (droneIns + newD).sortedBy { it.t }
                droneIns.clear(); droneIns.addAll(all)
            }
            saveDroneIns(sync = false)

            if (name.isEmpty()) name = st.name
            if (leader.isEmpty()) leader = st.leader
            if (shiftCode.isEmpty()) shiftCode = st.shiftCode

            activeRecT = flights.firstOrNull { it.lt == null }?.t
            val json = store.json
            store.putStringNow("kap_flights", json.encodeToString(flightsSer, flights.toList()))
            store.putStringNow("kap_events", json.encodeToString(eventsSer, events.toList()))
            store.putStringNow("kap_templates", json.encodeToString(ListSerializer(String.serializer()), templates.toList()))
            store.putStringNow("kap_points", json.encodeToString(ListSerializer(Point.serializer()), points.toList()))
            store.putStringNow("kap_fields", json.encodeToString(ListSerializer(Field.serializer()), fields.toList()))
            bump()
            syncPreview()
        } finally {
            applyingRemote = false
        }
    }

    companion object {
        const val HUB_PORT = 8777
        /** Flights/events are kept this long (report archive). */
        const val KEEP_DAYS = 21
        private val flightsSer = ListSerializer(Flight.serializer())
        private val eventsSer = ListSerializer(Event.serializer())

        /** Normalises legacy data: inline MGRS in comments → formatted, flight mgrs → formatted. */
        fun normFlight(f: Flight): Flight = f.copy(
            c = Coords.INLINE_COORD.replace(f.c) { m ->
                val (zone, sq, e, n) = m.destructured
                if (e.length == n.length) Coords.fmtMGRS(zone + sq + e + n) else m.value
            },
            mgrs = Coords.fmtMGRS(f.mgrs),
        )

        /** First site-local IPv4 of an up, non-loopback interface (shown on the hub QR screen). */
        fun hubLocalIp(): String = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress ?: "—"
        } catch (e: Exception) {
            "—"
        }
    }
}
