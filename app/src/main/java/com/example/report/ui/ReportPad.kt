package com.example.report.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.report.ui.theme.OkGreen

/** Full-screen editor of the report text (preview); manual edits are kept until reset with ↺. */
@Composable
fun ReportPad(vm: ReportViewModel, share: () -> Unit, copy: () -> Unit, close: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    BackHandler(onBack = close)
    LaunchedEffect(Unit) { vm.updatePreview(false) }

    val modeLabel = when (vm.mode) {
        "day" -> "День"
        "night" -> "Ніч"
        "shift" -> "Доба"
        "week" -> "7 днів"
        else -> "Зараз"
    }

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "ЗВІТ · $modeLabel",
                    Modifier.weight(1f),
                    color = cs.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (vm.reportEdited()) {
                    IconButton(onClick = { vm.resetReport() }) {
                        Icon(Icons.Filled.Refresh, "Повернути автоматичний", tint = cs.onSurface)
                    }
                }
                IconButton(onClick = copy) {
                    Icon(Icons.Filled.ContentCopy, "Копіювати", tint = cs.onSurface)
                }
                IconButton(onClick = share) {
                    Icon(Icons.Filled.Share, "Поділитися", tint = cs.onSurface)
                }
                IconButton(onClick = close) {
                    Icon(Icons.Filled.Close, "Закрити", tint = cs.onSurface)
                }
            }
            HorizontalDivider(color = cs.outline)

            BasicTextField(
                value = vm.previewText,
                onValueChange = { vm.editPreview(it) },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(cs.onSurface),
                decorationBox = { inner -> Box { inner() } },
            )
            HorizontalDivider(color = cs.outline)

            val edited = vm.reportEdited()
            Text(
                if (edited) "Правлено вручну · ↺ — повернути автоматичний" else "Автоматичний звіт · можна правити",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = if (edited) OkGreen else cs.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}
