package com.example.report.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.report.core.Coords
import com.example.report.core.Mgrs
import com.example.report.core.TimeFmt
import com.example.report.data.Point
import com.example.report.ui.theme.ErrRed
import com.example.report.ui.theme.OkGreen
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Map
import com.example.report.data.Route
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.automirrored.filled.Undo
import org.osmdroid.views.overlay.Marker
import kotlin.math.ceil
import kotlin.math.max
import android.graphics.Color as AColor

/** Palette offered for map points (first = default for new points, see ReportViewModel.addPoint). */
val PT_COLORS = listOf("#c0392b", "#e67e22", "#f1c40f", "#1f7a1f", "#2980b9", "#8e44ad", "#111111", "#ffffff")

/** Esri World Imagery satellite tiles ({z}/{y}/{x}, no extension). */
val ArcGisSat = object : OnlineTileSourceBase(
    "ArcGISSat", 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

// TODO(decomp): the brief mentions an OSM/ArcGIS basemap toggle; the APK has none here —
//  the map always uses ArcGisSat and `vm.baseMap` ("sat") is loaded but never read by the UI.

/**
 * Full-screen satellite map. Long-press adds a point (MGRS shown top-left), tapping a point
 * opens [PointMenu]; "У звіт" there opens [CoordPicker] to attach the MGRS to an event,
 * the drone-loss line, a comment line or the flight itself.
 */
@Composable
fun MapScreen(vm: ReportViewModel, close: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = close)

    var selPoint by remember { mutableStateOf<Point?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var rev by remember { mutableIntStateOf(0) }
    var myLoc by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var compassOn by remember { mutableStateOf(false) }
    /** 0 = off, 1 = by taps (straight segments), 2 = freehand. */
    var drawMode by remember { mutableIntStateOf(0) }
    var selRoute by remember { mutableStateOf<Route?>(null) }
    var showRoutes by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showDrawMenu by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(vm.store.bool("kap_map_offline")) }
    val heading = rememberHeading(compassOn)

    val mapView = remember {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = "Mozilla/5.0 (Linux; Android)" // don't announce the app id to the tile server
        // Tile cache in the app's private folder (not shared storage).
        Configuration.getInstance().osmdroidBasePath = java.io.File(context.filesDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = java.io.File(context.filesDir, "osmdroid/tiles")
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 2_000L * 1024 * 1024
        MapView(context).apply {
            setTileSource(ArcGisSat)
            setMultiTouchControls(true)
            setUseDataConnection(!vm.store.bool("kap_map_offline"))
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(49.0, 32.0))
        }
    }
    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }
    LaunchedEffect(compassOn, heading) {
        mapView.mapOrientation = if (compassOn && heading != null) -heading else 0f
        mapView.invalidate()
    }
    val screenH = LocalConfiguration.current.screenHeightDp

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
                map.overlays.clear()
                map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        if (drawMode != 1) return false
                        vm.routeAdd(listOf(p.latitude to p.longitude))
                        rev++
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint): Boolean {
                        label = vm.addPoint(p.latitude, p.longitude).mgrs
                        if (vm.strikeCoordTarget) picked = label
                        rev++
                        (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                            ?.vibrate(VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE))
                        return true
                    }
                }))
                rev // read so that point edits redraw the markers
                // saved routes (visible ones); tap a line -> route menu
                for (r in vm.routes) {
                    if (!r.visible || r.pts.size < 2) continue
                    val col = parseColor(r.color)
                    val geo = r.pts.map { (la, lo) -> GeoPoint(la, lo) }
                    if (r.closed && r.pts.size >= 3) {
                        val pg = org.osmdroid.views.overlay.Polygon(map)
                        pg.points = geo + geo.first()
                        pg.outlinePaint.color = col
                        pg.outlinePaint.strokeWidth = 6f
                        pg.fillPaint.color = (col and 0x00FFFFFF) or 0x22000000
                        pg.setOnClickListener { _, _, _ -> if (drawMode == 0) { selRoute = r; true } else false }
                        map.overlays.add(pg)
                    } else {
                        val line = Polyline(map)
                        line.setPoints(geo)
                        line.outlinePaint.color = col
                        line.outlinePaint.strokeWidth = 7f
                        line.setOnClickListener { _, _, _ -> if (drawMode == 0) { selRoute = r; true } else false }
                        map.overlays.add(line)
                    }
                    // name label (like DJI Pilot): inside the area / at the middle of the route
                    val lm = Marker(map)
                    val (la, lo) = labelPos(r)
                    lm.position = GeoPoint(la, lo)
                    lm.icon = BitmapDrawable(context.resources, labelBitmap(r.name, col, context.resources.displayMetrics.density))
                    lm.setAnchor(0.5f, 0.5f)
                    lm.setInfoWindow(null)
                    lm.setOnMarkerClickListener { _, _ -> if (drawMode == 0) { selRoute = r; true } else false }
                    map.overlays.add(lm)
                }
                // route being drawn
                if (vm.route.isNotEmpty()) {
                    val geo = vm.route.map { (la, lo) -> GeoPoint(la, lo) }
                    if (vm.drawClosed && geo.size >= 3) {
                        val pg = org.osmdroid.views.overlay.Polygon(map)
                        pg.points = geo + geo.first()
                        pg.outlinePaint.color = AColor.parseColor("#f1c40f")
                        pg.outlinePaint.strokeWidth = 7f
                        pg.fillPaint.color = 0x22f1c40f
                        pg.setOnClickListener { _, _, _ -> false }
                        map.overlays.add(pg)
                    } else {
                        val line = Polyline(map)
                        line.setPoints(geo)
                        line.outlinePaint.color = AColor.parseColor("#f1c40f")
                        line.outlinePaint.strokeWidth = 8f
                        line.setOnClickListener { _, _, _ -> false }
                        map.overlays.add(line)
                    }
                    val dots = if (vm.route.size <= 60) vm.route else listOf(vm.route.first(), vm.route.last())
                    for ((la, lo) in dots) {
                        val v = Marker(map)
                        v.position = GeoPoint(la, lo)
                        v.setAnchor(0.5f, 0.5f)
                        v.icon = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL; setColor(AColor.parseColor("#f1c40f")); setStroke(3, AColor.BLACK); setSize(22, 22)
                        }
                        v.setInfoWindow(null)
                        v.setOnMarkerClickListener { _, _ -> false }
                        map.overlays.add(v)
                    }
                }
                for (pt in vm.points) {
                    val m = Marker(map)
                    m.position = GeoPoint(pt.lat, pt.lng)
                    val (icon, anchorV) = pointIcon(pt.name, pt.color)
                    m.icon = BitmapDrawable(icon)
                    m.setAnchor(0.5f, anchorV)
                    m.setInfoWindow(null)
                    m.setOnMarkerClickListener { _, _ ->
                        selPoint = pt
                        true
                    }
                    map.overlays.add(m)
                }
                myLoc?.let { (lat, lng) ->
                    val m = Marker(map)
                    m.position = GeoPoint(lat, lng)
                    m.setAnchor(0.5f, 0.5f)
                    m.icon = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(AColor.parseColor("#2196F3"))
                        setStroke(5, AColor.WHITE)
                        setSize(34, 34)
                    }
                    m.setInfoWindow(null)
                    map.overlays.add(m)
                }
                if (drawMode == 2) {
                    map.overlays.add(FreehandOverlay(AColor.parseColor("#f1c40f")) { stroke -> vm.routeAdd(stroke); rev++ })
                }
                drawMode // re-run when the mode changes
                vm.routes.size
                map.invalidate()
            },
        )

        Column(
            Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
        ) {
            if (label.isNotEmpty()) MapLabel(label)
            rev
            if (drawMode != 0 || vm.route.size > 1) {
                MapLabel((if (vm.drawClosed) "Область: " else "Маршрут: ") + shapeInfo(vm.route, vm.drawClosed) +
                    when {
                        drawMode == 1 && vm.route.isEmpty() -> " · тапайте по карті"
                        drawMode == 2 && vm.route.isEmpty() -> " · ведіть пальцем"
                        else -> ""
                    })
            }
            if (vm.strikeCoordTarget) MapLabel("Ураження: довге натискання — координати цілі")
            if (compassOn && heading != null) MapLabel("курс " + heading.toInt() + "°")
        }

        Column(
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            MapIconBtn(Icons.Filled.Close, "Закрити", onClick = { close() })
            MapIconBtn(Icons.Filled.MyLocation, "Моє місце", onClick = {
                val loc = lastKnownLocation(context)
                if (loc == null) {
                    vm.setStatus("Не вдалося визначити місце (дозвольте GPS)", "err")
                } else {
                    myLoc = loc
                    mapView.controller.setZoom(15.0)
                    mapView.controller.setCenter(GeoPoint(loc.first, loc.second))
                    label = Coords.fmtMGRS(Mgrs.forward(loc.second, loc.first))
                    rev++
                }
            })
            MapIconBtn(Icons.Filled.Explore, "Компас", compassOn, onClick = { compassOn = !compassOn })
            MapIconBtn(Icons.Filled.Edit, "Малювання", drawMode != 0, onClick = { showDrawMenu = true })
            MapIconBtn(if (offline) Icons.Filled.CloudOff else Icons.Filled.Cloud, "Офлайн-карта", offline, onClick = { showOffline = true })
            MapIconBtn(Icons.Filled.DeleteSweep, "Очистити точки", onClick = {
                if (vm.points.isNotEmpty()) {
                    vm.clearPoints()
                    rev++
                }
            })
        }

        if (vm.route.isNotEmpty()) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 8.dp, bottom = 24.dp),
            ) {
                MapIconBtn(Icons.AutoMirrored.Filled.Undo, "Крок назад", onClick = { vm.routeUndo(); rev++ })
                MapIconBtn(Icons.Filled.Save, "Зберегти маршрут", onClick = { showSave = true })
                MapIconBtn(Icons.Filled.LinearScale, "Очистити маршрут", onClick = { vm.routeClear(); rev++ })
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(end = 8.dp, bottom = (screenH * 0.25f).dp),
        ) {
            MapIconBtn(Icons.Filled.Add, "Збільшити", onClick = { mapView.controller.zoomIn() })
            MapIconBtn(Icons.Filled.Remove, "Зменшити", onClick = { mapView.controller.zoomOut() })
        }
    }

    selPoint?.let { point ->
        PointMenu(
            point = point,
            onName = { name ->
                vm.updatePoint(point.id, name, null)
                selPoint = point.copy(name = name)
                rev++
            },
            onColor = { color ->
                vm.updatePoint(point.id, null, color)
                selPoint = point.copy(color = color)
                rev++
            },
            onUse = {
                selPoint = null
                picked = point.mgrs
            },
            onDelete = {
                vm.removePoint(point.id)
                selPoint = null
                rev++
            },
            onDismiss = { selPoint = null },
        )
    }

    if (showOffline) OfflineMapDialog(
        mapView, offline,
        onOffline = { on -> offline = on; vm.store.putBool("kap_map_offline", on); mapView.setUseDataConnection(!on); mapView.invalidate() },
    ) { showOffline = false }
    if (showDrawMenu) DrawMenu(
        drawMode, vm.drawClosed, vm.route.isNotEmpty(),
        onPick = { m, closed -> drawMode = m; vm.drawClosed = closed; showDrawMenu = false; rev++ },
        onSaved = { showDrawMenu = false; showRoutes = true },
        onStop = { drawMode = 0; showDrawMenu = false; rev++ },
    ) { showDrawMenu = false }
    if (showRoutes) RoutesList(vm, { r -> selRoute = r; showRoutes = false }) { showRoutes = false }
    selRoute?.let { r ->
        RouteMenu(
            vm, r,
            onShow = {
                if (r.pts.size > 1) mapView.post {
                    mapView.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(r.pts.map { (la, lo) -> GeoPoint(la, lo) }), true, 80)
                }
            },
            onEdit = { vm.editRoute(r.id); drawMode = 1; rev++ },
            onDismiss = { selRoute = null; rev++ },
        )
    }
    if (showSave) {
        val cur = vm.routes.firstOrNull { it.id == vm.routeEditId }
        RouteSaveDialog(
            (if (vm.drawClosed) "Зберегти область · " else "Зберегти маршрут · ") + shapeInfo(vm.route, vm.drawClosed),
            cur?.name ?: "", cur?.color ?: ROUTE_COLORS.first(),
            { n, c -> vm.saveRoute(n, c); showSave = false; drawMode = 0; rev++ },
        ) { showSave = false }
    }

    DisposableEffect(Unit) {
        onDispose { vm.eventCoordTarget = null; vm.strikeCoordTarget = false }
    }

    picked?.let { mgrs ->
        // Comment lines of the active (or last) flight that can take coordinates.
        val rec = vm.activeRec() ?: vm.flights.lastOrNull()
        val lines: List<Pair<Int, String>> = rec?.c?.split("\n")?.withIndex()?.mapNotNull { (i, raw) ->
            val text = Coords.TIME_RE.replace(raw.trim(), "")
            if (text.isNotEmpty() && !Coords.isCoord(text)) Pair(i, text) else null
        } ?: emptyList()

        val eventLabel = vm.eventCoordTarget?.let { vm.events.getOrNull(it) }?.let { e ->
            TimeFmt.hm(e.t) + (e.text.trim().ifEmpty { null }?.let { " $it" } ?: "")
        }

        val onLoss: (() -> Unit)? = if (vm.lossDraft != null || vm.lossLineTarget() != null) {
            {
                vm.lossLineTarget()?.let { (f, idx) ->
                    val ls = ArrayList(f.c.split("\n"))
                    ls[idx] = Coords.COORD_RE.replace(ls[idx], "").trimEnd() + " за координатами " + mgrs
                    vm.setFlightComment(f.t, ls.joinToString("\n"))
                }
                // TODO(decomp): inlined in the APK as `lossDraft = lossDraft.copy(coords = mgrs)`.
                vm.updateLoss(coords = mgrs)
                vm.setStatus("✓ Координати у втрату дрону", "ok")
                picked = null
            }
        } else {
            null
        }

        CoordPicker(
            mgrs = mgrs,
            lines = lines,
            eventLabel = eventLabel,
            onEvent = {
                vm.eventCoordTarget?.let { idx ->
                    vm.updateEvent(idx) { it.copy(mgrs = mgrs.trim()) }
                    vm.eventCoordTarget = null
                    vm.setStatus("✓ Координати у подію", "ok")
                }
                picked = null
                close()
            },
            onLine = { idx ->
                vm.attachCoordsToLine(mgrs, idx)
                vm.setStatus("✓ Координати підставлено", "ok")
                picked = null
            },
            onFlight = {
                vm.attachCoordsToFlight(mgrs)
                picked = null
            },
            hasFlight = rec != null,
            onDismiss = { picked = null },
            onLoss = onLoss,
            onStrike = if (vm.strikeCoordTarget) ({
                vm.updateStrike { it.copy(coords = mgrs.trim()) }
                vm.strikeCoordTarget = false
                vm.setStatus("✓ Координати в ураження", "ok")
                picked = null
                close()
            }) else null,
        )
    }
}

/** Best (most accurate) last-known fix over all enabled providers, as (lat, lng). */
private fun lastKnownLocation(context: Context): Pair<Double, Double>? {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return try {
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = lm.getLastKnownLocation(p) ?: continue
            if (best == null || l.accuracy < best.accuracy) best = l
        }
        best?.let { Pair(it.latitude, it.longitude) }
    } catch (e: SecurityException) {
        null
    }
}

/**
 * Marker bitmap: optional white name plate on top, then a colored circle with white ring.
 * Returns the bitmap and the vertical anchor (circle centre) as a fraction of its height.
 * TODO(decomp): inlined into the AndroidView update lambda in the APK.
 */
private fun pointIcon(name: String, color: String): Pair<Bitmap, Float> {
    val blank = name.isBlank()
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 42f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    val fm = text.fontMetrics
    val plateH = if (!blank) fm.descent - fm.ascent + 10f else 0f
    val plateW = if (!blank) text.measureText(name) + 20f else 0f
    val gap = if (!blank) 6f else 0f
    val w = max(90f, plateW).coerceAtLeast(1f)
    val h = plateH + gap + 90f
    val bmp = Bitmap.createBitmap(ceil(w).toInt(), ceil(h).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = w / 2f
    if (!blank) {
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = AColor.argb(235, 255, 255, 255) }
        canvas.drawRoundRect(cx - plateW / 2f, 0f, cx + plateW / 2f, plateH, 7f, 7f, plate)
        text.color = AColor.BLACK
        canvas.drawText(name, cx, plateH / 2f - (fm.ascent + fm.descent) / 2f, text)
    }
    val cy = h - 45f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = runCatching { AColor.parseColor(color) }.getOrDefault(AColor.RED)
    }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = AColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    canvas.drawCircle(cx, cy, 41f, fill)
    canvas.drawCircle(cx, cy, 41f, ring)
    return Pair(bmp, cy / h)
}

/** Round map button: icon with a soft drop shadow; [active] tints it green. */
@Composable
private fun MapIconBtn(icon: ImageVector, desc: String, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(2.dp)) {
        Box {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(start = 1.5.dp, top = 1.5.dp),
                tint = Color.Black.copy(alpha = 0.55f),
            )
            Icon(icon, contentDescription = desc, tint = if (active) Color(0xFF7EE787) else Color.White)
        }
    }
}

/** Translucent dark chip with white text, used over the map. */
@Composable
private fun MapLabel(text: String) {
    Surface(
        modifier = Modifier.padding(bottom = 4.dp),
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = Color.White, fontSize = 12.sp)
    }
}

/** Point editor: name, color and "use in report". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PointMenu(
    point: Point,
    onName: (String) -> Unit,
    onColor: (String) -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onUse) { Text("У звіт", color = OkGreen) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Видалити", color = ErrRed) }
                TextButton(onClick = onDismiss) { Text("Закрити") }
            }
        },
        title = { Text("Точка") },
        text = {
            Column {
                var name by remember(point.id) { mutableStateOf(point.name) }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        onName(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Назва точки") },
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in PT_COLORS) {
                        val col = Color(runCatching { AColor.parseColor(c) }.getOrDefault(AColor.RED))
                        Surface(
                            modifier = Modifier.size(28.dp).clip(CircleShape),
                            shape = CircleShape,
                            color = col,
                        ) {
                            TextButton(onClick = { onColor(c) }, contentPadding = PaddingValues(0.dp)) {}
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(point.mgrs, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        },
    )
}

/**
 * Where to put picked coordinates: the event awaiting them ([eventLabel]), the drone-loss
 * report ([onLoss]), one of the flight's comment [lines], or the flight as a whole.
 */
@Composable
private fun CoordPicker(
    mgrs: String,
    lines: List<Pair<Int, String>>,
    eventLabel: String?,
    onEvent: () -> Unit,
    onLine: (Int) -> Unit,
    onFlight: () -> Unit,
    hasFlight: Boolean,
    onDismiss: () -> Unit,
    onLoss: (() -> Unit)?,
    onStrike: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (hasFlight) {
                TextButton(onClick = onFlight) { Text("До вильоту", color = OkGreen) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрити") }
        },
        title = { Text("Підставити координати") },
        text = {
            Column {
                Text(mgrs, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.size(8.dp))
                if (onStrike != null) {
                    TextButton(onClick = onStrike, modifier = Modifier.fillMaxWidth()) {
                        Text("В ураження (ціль)", color = ErrRed, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
                if (eventLabel != null) {
                    TextButton(onClick = onEvent, modifier = Modifier.fillMaxWidth()) {
                        Text("У подію: $eventLabel", color = OkGreen, maxLines = 1)
                    }
                }
                if (onLoss != null) {
                    TextButton(onClick = onLoss, modifier = Modifier.fillMaxWidth()) {
                        Text("У втрату дрону", color = ErrRed)
                    }
                }
                if (!hasFlight) {
                    Text("Немає вильоту — спершу «Взліт».", fontSize = 12.sp)
                } else if (lines.isEmpty()) {
                    Text("У цьому вильоті ще немає шаблонів. Підставте до вильоту загалом.", fontSize = 12.sp)
                }
                for ((idx, text) in lines) {
                    TextButton(onClick = { onLine(idx) }, modifier = Modifier.fillMaxWidth()) {
                        Text(text)
                    }
                }
            }
        },
    )
}

/** Route length in metres (haversine over consecutive points). */
fun routeLength(pts: List<Pair<Double, Double>>): Double {
    var d = 0.0
    for (i in 1 until pts.size) {
        val (la1, lo1) = pts[i - 1]; val (la2, lo2) = pts[i]
        val r = 6_371_000.0
        val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2)
        val dp = p2 - p1; val dl = Math.toRadians(lo2 - lo1)
        val a = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
        d += 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
    return d
}

fun fmtDist(m: Double): String = if (m < 1000) "${m.toInt()} м" else String.format(java.util.Locale.US, "%.2f км", m / 1000)

/** Offline map: «only cached tiles» switch + download of the visible area for a zoom range. */
@Composable
private fun OfflineMapDialog(mapView: MapView, offline: Boolean, onOffline: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val z = mapView.zoomLevelDouble.toInt().coerceIn(1, 18)
    var zMax by remember { mutableIntStateOf(minOf(z + 3, 18)) }
    val bbox = remember { mapView.boundingBox }
    val cm = remember { org.osmdroid.tileprovider.cachemanager.CacheManager(mapView) }
    val tiles = remember(zMax) { cm.possibleTilesInArea(bbox, z, zMax) }
    val cacheMb = remember { (java.io.File(context.filesDir, "osmdroid").walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_048_576) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Офлайн-карта") },
        text = {
            Column {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Тільки збережені тайли (без інтернету)", Modifier.weight(1f), fontSize = 14.sp)
                    androidx.compose.material3.Switch(offline, onOffline)
                }
                Text("У цьому режимі карта нічого не завантажує — сервер карт не бачить, де ви дивитесь.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                Text("Зберегти видимий район: зум $z–$zMax · $tiles тайлів (~${tiles * 25 / 1024 + 1} МБ)", fontSize = 13.sp)
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { if (zMax > z) zMax-- }) { Text("− деталь") }
                    TextButton(onClick = { if (zMax < 18) zMax++ }) { Text("+ деталь") }
                }
                Text("Завантажуйте заздалегідь, у безпечному місці: під час завантаження сервер карт бачить цей район.",
                    fontSize = 12.sp, color = ErrRed)
                Spacer(Modifier.size(8.dp))
                Text("Збережено: $cacheMb МБ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                cm.downloadAreaAsync(context, bbox, z, zMax)
                onDismiss()
            }, enabled = tiles in 1..60_000) { Text("Завантажити", color = OkGreen) }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = {
                    java.io.File(context.filesDir, "osmdroid").deleteRecursively(); onDismiss()
                }) { Text("Очистити кеш", color = ErrRed) }
                TextButton(onClick = onDismiss) { Text("Закрити") }
            }
        },
    )
}
