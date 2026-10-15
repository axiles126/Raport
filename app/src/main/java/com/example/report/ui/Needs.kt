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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.report.ui.theme.ErrRed
import com.example.report.ui.theme.OkGreen

/** Full-screen notepad for the position's needs; one line = one item, optionally numbered. */
@Composable
fun NeedsScreen(vm: ReportViewModel, share: () -> Unit, close: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var confirmClear by remember { mutableStateOf(false) }
    BackHandler(onBack = close)

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "ПОТРЕБИ",
                    Modifier.weight(1f),
                    color = cs.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = { vm.toggleNeedsNumbered() }) {
                    Icon(
                        Icons.Filled.FormatListNumbered,
                        "Нумерація",
                        tint = if (vm.needsNumbered) OkGreen else cs.onSurfaceVariant,
                    )
                }
                IconButton(onClick = share, enabled = vm.needsText.isNotBlank()) {
                    Icon(
                        Icons.Filled.Share,
                        "Поділитися",
                        tint = if (vm.needsText.isNotBlank()) cs.onSurface else cs.outline,
                    )
                }
                IconButton(onClick = { confirmClear = true }, enabled = vm.needsText.isNotEmpty()) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        "Очистити",
                        tint = if (vm.needsText.isNotEmpty()) ErrRed else cs.outline,
                    )
                }
                IconButton(onClick = close) {
                    Icon(Icons.Filled.Close, "Закрити", tint = cs.onSurface)
                }
            }
            HorizontalDivider(color = cs.outline)

            val numColor = cs.onSurfaceVariant
            val transformation = remember(vm.needsNumbered, numColor) {
                if (vm.needsNumbered) NumberedParagraphs(numColor) else VisualTransformation.None
            }
            BasicTextField(
                value = vm.needsText,
                onValueChange = { vm.changeNeedsText(it) },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp, lineHeight = 24.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                visualTransformation = transformation,
                cursorBrush = SolidColor(cs.onSurface),
                decorationBox = { inner ->
                    Box {
                        if (vm.needsText.isEmpty()) {
                            Text(
                                "Що потрібно позиції.\nКожен Enter — новий пункт.",
                                color = cs.outline,
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 24.sp,
                            )
                        }
                        inner()
                    }
                },
            )
            HorizontalDivider(color = cs.outline)

            val count = vm.needsText.split('\n').count { it.isNotBlank() }
            Text(
                "Пунктів: $count · зберігається автоматично",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = {
                TextButton(onClick = {
                    // TODO(decomp): inlined in the APK; could be a VM method (e.g. clearNeeds()).
                    vm.needsText = ""
                    vm.store.putStringNow("kap_needs", "")
                    confirmClear = false
                }) {
                    Text("Очистити", color = ErrRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Скасувати") }
            },
            title = { Text("Очистити потреби?") },
            text = { Text("Весь текст буде видалено.") },
        )
    }
}
