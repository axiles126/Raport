package com.example.report.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.report.core.TimeFmt
import com.example.report.ui.theme.ErrRed
import kotlinx.coroutines.delay

private val HM_RE = Regex("^\\d{1,2}:\\d{2}$")

/** Clickable without the ripple indication. */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

/** Keeps only ASCII digits, at most 3 of them. */
fun digits(s: String): String = s.filter { it in '0'..'9' }.take(3)

/** Small bold section header. */
@Composable
fun ehead(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
}

/** Label (fixed 120dp) + control row. */
@Composable
fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.width(120.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        content()
    }
}

/** "label  [−] n [+]" counter, never below zero. */
@Composable
fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        TextButton(onClick = { onChange((value - 1).coerceAtLeast(0)) }) {
            Text("−", fontSize = 18.sp)
        }
        Text(value.toString(), Modifier.width(28.dp), textAlign = TextAlign.Center)
        TextButton(onClick = { onChange(value + 1) }) {
            Text("+", fontSize = 18.sp)
        }
    }
}

/**
 * "ГГ:ХХ" time box (accepts "0600" etc.). Reports the normalised value only when it is
 * valid and differs from [value]; shows an error while the text is not a valid time.
 */
@Composable
fun TimeBox(value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val norm = TimeFmt.normHm(it)
            if (norm != null && norm != value) onChange(norm)
        },
        modifier = Modifier.width(92.dp),
        placeholder = { Text("ГГ:ХХ") },
        isError = text.isNotEmpty() && TimeFmt.normHm(text) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

/** Compact "HH:MM" field; passes through empty text or a valid H:MM / HH:MM. */
@Composable
fun TimeField(value: String, width: Int = 90, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (it.isEmpty() || HM_RE.matches(it)) onChange(it)
        },
        modifier = Modifier.width(width.dp).height(58.dp),
        textStyle = MaterialTheme.typography.bodySmall,
        placeholder = { Text("--:--", fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

/** Name + quantity row (propellers / drones) with a remove button. */
@Composable
fun NamedQtyRow(
    name: String,
    qty: Int,
    placeholder: String,
    onName: (String) -> Unit,
    onQty: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        var n by remember(name) { mutableStateOf(name) }
        OutlinedTextField(
            value = n,
            onValueChange = {
                n = it
                onName(it)
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder) },
            singleLine = true,
        )
        Spacer(Modifier.width(4.dp))
        var q by remember(qty) { mutableStateOf(if (qty == 0) "" else qty.toString()) }
        OutlinedTextField(
            value = q,
            onValueChange = {
                q = digits(it).trimStart('0')
                onQty(q.toIntOrNull() ?: 0)
            },
            modifier = Modifier.width(80.dp),
            placeholder = { Text("0") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
        )
        TextButton(onClick = onRemove) {
            Text("✕", color = ErrRed)
        }
    }
}

/** "Скинути зміну" menu row: needs 3 taps within 4 s to fire [onReset]. */
@Composable
fun ResetMenuItem(onReset: () -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(taps) {
        if (taps in 1..2) {
            delay(4000)
            taps = 0
        }
    }
    Row(
        Modifier.fillMaxWidth().clickableNoRipple {
            taps++
            if (taps >= 3) {
                taps = 0
                onReset()
            }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = "Скинути зміну",
            tint = if (taps > 0) ErrRed else cs.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (taps > 0) "Скинути зміну — ще " + (3 - taps) else "Скинути зміну",
            color = if (taps > 0) ErrRed else cs.onSurface,
            fontSize = 13.sp,
        )
    }
}
