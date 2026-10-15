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

fun routeText(r: Route): String {
    val head = "Маршрут «${r.name}» · " + fmtDist(routeLength(r.pts))
    val rows = sample(r.pts).mapIndexed { i, (la, lo) -> "${i + 1}. " + Coords.fmtMGRS(Mgrs.forward(lo, la)) }
    return (listOf(head) + rows).joinToString("\n")
}

fun copyRoute(context: Context, r: Route) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Маршрут", routeText(r)))
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
                Text(fmtDist(routeLength(r.pts)) + " · точок " + r.pts.size, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
    if (rename) RouteSaveDialog("Маршрут", r.name, r.color, { n, c -> vm.updateRoute(r.id, n, c); rename = false; onDismiss() }) { rename = false }
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
        title = { Text("Маршрути") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (vm.routes.isEmpty()) Text("Збережених маршрутів немає.", color = MaterialTheme.colorScheme.outline)
                else Row {
                    TextButton(onClick = { vm.setAllRoutesVisible(true) }) { Text("Показати всі") }
                    TextButton(onClick = { vm.setAllRoutesVisible(false) }) { Text("Сховати всі") }
                }
                for (r in vm.routes) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(r) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(14.dp).background(Color(parseColor(r.color)), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(r.name, Modifier.weight(1f), maxLines = 1,
                            color = if (r.visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                        Text(fmtDist(routeLength(r.pts)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
