package com.example.report.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.report.core.Coords
import com.example.report.core.Mgrs
import com.example.report.data.Route
import com.example.report.ui.theme.ErrRed
import com.example.report.ui.theme.OkGreen
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import android.graphics.Color as AColor

/** Route colours (yellow first = default). */
val ROUTE_COLORS = listOf("#f1c40f", "#c0392b", "#e67e22", "#1f7a1f", "#2980b9", "#8e44ad", "#ffffff", "#111111")

/**
 * Freehand drawing: one finger draws (map does not pan), two fingers still zoom/pan.
 * The stroke is drawn live by this overlay and handed over in [onStroke] when the finger lifts.
 */
class FreehandOverlay(private val color: Int, private val onStroke: (List<Pair<Double, Double>>) -> Unit) : Overlay() {
    private val pts = ArrayList<GeoPoint>()
    private var lastX = 0f
    private var lastY = 0f
    private var active = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; this.color = color
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun onTouchEvent(e: MotionEvent, mapView: MapView): Boolean {
        if (e.pointerCount > 1) { if (active) finish(); return false }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pts.clear(); active = true; lastX = e.x; lastY = e.y
                pts.add(mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint)
            }
            MotionEvent.ACTION_MOVE -> if (active) {
                val dx = e.x - lastX; val dy = e.y - lastY
                if (dx * dx + dy * dy >= 14f * 14f) { // thin out: a point every ~14 px
                    lastX = e.x; lastY = e.y
                    pts.add(mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint)
                    mapView.invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (active) {
                pts.add(mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint)
                finish()
            }
        }
        return true
    }

    private fun finish() {
        active = false
        val out = pts.map { it.latitude to it.longitude }
        pts.clear()
        if (out.size >= 2) onStroke(out)
    }

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || pts.size < 2) return
        val p = Path(); val pt = android.graphics.Point()
        pts.forEachIndexed { i, g ->
            mapView.projection.toPixels(g, pt)
            if (i == 0) p.moveTo(pt.x.toFloat(), pt.y.toFloat()) else p.lineTo(pt.x.toFloat(), pt.y.toFloat())
        }
        c.drawPath(p, paint)
    }
}

fun parseColor(hex: String): Int = try { AColor.parseColor(hex) } catch (_: Throwable) { AColor.YELLOW }

/** Up to [max] evenly spaced points (first and last kept) — keeps freehand routes readable as text. */
private fun sample(pts: List<Pair<Double, Double>>, max: Int = 25): List<Pair<Double, Double>> {
    if (pts.size <= max) return pts
    return (0 until max).map { i -> pts[(i.toLong() * (pts.size - 1) / (max - 1)).toInt()] }
}

/** Closed-ring perimeter in metres. */
fun perimeter(pts: List<Pair<Double, Double>>): Double =
    if (pts.size < 3) routeLength(pts) else routeLength(pts + pts.first())

/** Polygon area in m² (local equirectangular projection + shoelace; fine for areas up to tens of km). */
fun areaM2(pts: List<Pair<Double, Double>>): Double {
    if (pts.size < 3) return 0.0
    val lat0 = Math.toRadians(pts.map { it.first }.average())
    val r = 6_371_000.0
    val xy = pts.map { (la, lo) -> Math.toRadians(lo) * r * Math.cos(lat0) to Math.toRadians(la) * r }
    var s = 0.0
    for (i in xy.indices) { val (x1, y1) = xy[i]; val (x2, y2) = xy[(i + 1) % xy.size]; s += x1 * y2 - x2 * y1 }
    return Math.abs(s) / 2
}

fun fmtArea(m2: Double): String = when {
    m2 < 10_000 -> "${m2.toInt()} м²"
    m2 < 1_000_000 -> String.format(java.util.Locale.US, "%.1f га", m2 / 10_000)
    else -> String.format(java.util.Locale.US, "%.2f км²", m2 / 1_000_000)
}

/** One-line summary: route → length; area → area + perimeter. */
fun shapeInfo(pts: List<Pair<Double, Double>>, closed: Boolean): String =
    if (closed) fmtArea(areaM2(pts)) + " · периметр " + fmtDist(perimeter(pts)) else fmtDist(routeLength(pts))

/** Name label drawn on the map: text in the shape's colour with a dark outline (like DJI Pilot). */
fun labelBitmap(text: String, color: Int, density: Float): android.graphics.Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * density; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val w = (p.measureText(text) + 12 * density).toInt().coerceAtLeast(1)
    val h = (p.textSize * 1.5f).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density; this.color = color }
    c.drawRect(density, density, w - density, h - density, box)
    p.style = Paint.Style.STROKE; p.strokeWidth = 3 * density; p.color = AColor.BLACK
    c.drawText(text, 6 * density, p.textSize * 1.1f, p)
    p.style = Paint.Style.FILL; p.color = color
    c.drawText(text, 6 * density, p.textSize * 1.1f, p)
    return bmp
}

/** Label anchor: centroid for areas, middle vertex for routes. */
fun labelPos(r: Route): Pair<Double, Double> =
    if (r.closed) r.pts.map { it.first }.average() to r.pts.map { it.second }.average() else r.pts[r.pts.size / 2]

fun routeText(r: Route): String {
    val head = (if (r.closed) "Область «${r.name}» · " else "Маршрут «${r.name}» · ") + shapeInfo(r.pts, r.closed)
    val rows = sample(r.pts).mapIndexed { i, (la, lo) -> "${i + 1}. " + Coords.fmtMGRS(Mgrs.forward(lo, la)) }
    return (listOf(head) + rows).joinToString("\n")
}

fun copyRoute(context: Context, r: Route) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(com.example.report.core.sensitiveClip(ClipData.newPlainText("Маршрут", routeText(r))))
}

fun shareRoute(context: Context, r: Route) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, routeText(r)); putExtra(Intent.EXTRA_SUBJECT, "Маршрут ${r.name}")
    }
    context.startActivity(Intent.createChooser(intent, "Поділитися маршрутом"))
}

@Composable
fun ColorChips(selected: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in ROUTE_COLORS) {
            Box(
                Modifier.size(30.dp).background(Color(parseColor(c)), CircleShape)
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.onSurface else Color.Gray, CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

/** Name + colour for the route being saved (or the saved route being renamed/recoloured). */
@Composable
fun RouteSaveDialog(title: String, name0: String, color0: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(name0) }
    var color by remember { mutableStateOf(color0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Назва") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                ColorChips(color) { color = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, color) }) { Text("Зберегти", color = OkGreen) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

/** Actions for a saved route. */
@Composable
fun RouteMenu(vm: ReportViewModel, r: Route, onShow: () -> Unit, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var rename by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp).background(Color(parseColor(r.color)), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(r.name, maxLines = 1)
            }
        },
        text = {
            Column {
                Text((if (r.closed) "Область · " else "Маршрут · ") + shapeInfo(r.pts, r.closed), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { shareRoute(context, r); onDismiss() }, Modifier.fillMaxWidth()) { Text("Поділитися", color = OkGreen, fontWeight = FontWeight.Bold) }
                TextButton(onClick = { copyRoute(context, r); vm.setStatus("✓ Координати маршруту скопійовано", "ok"); onDismiss() }, Modifier.fillMaxWidth()) { Text("Копіювати координати") }
                TextButton(onClick = { vm.setRouteVisible(r.id, true); onShow(); onDismiss() }, Modifier.fillMaxWidth()) { Text("Показати на карті") }
                TextButton(onClick = { vm.setRouteVisible(r.id, !r.visible); onDismiss() }, Modifier.fillMaxWidth()) {
                    Text(if (r.visible) "Сховати з карти" else "Показувати на карті")
                }
                TextButton(onClick = { rename = true }, Modifier.fillMaxWidth()) { Text("Назва / колір") }
                TextButton(onClick = { onEdit(); onDismiss() }, Modifier.fillMaxWidth()) { Text("Редагувати лінію") }
                TextButton(onClick = { confirmDel = true }, Modifier.fillMaxWidth()) { Text("Видалити", color = ErrRed) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
    )
    if (rename) RouteSaveDialog(if (r.closed) "Область" else "Маршрут", r.name, r.color, { n, c -> vm.updateRoute(r.id, n, c); rename = false; onDismiss() }) { rename = false }
    if (confirmDel) AlertDialog(
        onDismissRequest = { confirmDel = false },
        title = { Text("Видалити «${r.name}»?") },
        confirmButton = { TextButton(onClick = { vm.deleteRoute(r.id); confirmDel = false; onDismiss() }) { Text("Видалити", color = ErrRed) } },
        dismissButton = { TextButton(onClick = { confirmDel = false }) { Text("Скасувати") } },
    )
}

/** List of saved routes; tap opens [RouteMenu]. */
@Composable
fun RoutesList(vm: ReportViewModel, onPick: (Route) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Маршрути та області") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (vm.routes.isEmpty()) Text("Збережених маршрутів і областей немає.", color = MaterialTheme.colorScheme.outline)
                else Row {
                    TextButton(onClick = { vm.setAllRoutesVisible(true) }) { Text("Показати всі") }
                    TextButton(onClick = { vm.setAllRoutesVisible(false) }) { Text("Сховати всі") }
                }
                for (r in vm.routes) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(r) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(14.dp).then(
                                if (r.closed) Modifier.border(3.dp, Color(parseColor(r.color)))
                                else Modifier.background(Color(parseColor(r.color)), CircleShape),
                            ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(r.name, Modifier.weight(1f), maxLines = 1,
                            color = if (r.visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                        Text(if (r.closed) fmtArea(areaM2(r.pts)) else fmtDist(routeLength(r.pts)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        androidx.compose.material3.IconButton(onClick = { vm.setRouteVisible(r.id, !r.visible) }) {
                            androidx.compose.material3.Icon(
                                if (r.visible) androidx.compose.material.icons.Icons.Filled.Visibility
                                else androidx.compose.material.icons.Icons.Filled.VisibilityOff,
                                if (r.visible) "Сховати" else "Показати",
                                tint = if (r.visible) OkGreen else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
    )
}

/** One menu for all drawing tools. mode: 0 off, 1 taps, 2 freehand; closed: area vs route. */
@Composable
fun DrawMenu(
    mode: Int, closed: Boolean, hasDrawing: Boolean,
    onPick: (mode: Int, closed: Boolean) -> Unit, onSaved: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit,
) {
    @Composable
    fun item(text: String, active: Boolean, onClick: () -> Unit) =
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(text, color = if (active) OkGreen else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Малювання") },
        text = {
            Column {
                Text("ОБЛАСТЬ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                item("▢  Область точками (тап — вершина)", mode == 1 && closed) { onPick(1, true) }
                item("〰  Область від руки (обвести пальцем)", mode == 2 && closed) { onPick(2, true) }
                Spacer(Modifier.height(6.dp))
                Text("МАРШРУТ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                item("⟋  Маршрут точками", mode == 1 && !closed) { onPick(1, false) }
                item("〰  Маршрут від руки", mode == 2 && !closed) { onPick(2, false) }
                HorizontalDivider()
                item("☰  Збережені маршрути та області", false, onSaved)
                if (mode != 0 || hasDrawing) item("■  Зупинити малювання", false, onStop)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
    )
}
