package com.example.report.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.report.core.AppLock
import com.example.report.data.SecurePrefs
import com.example.report.ui.theme.ErrRed
import com.example.report.ui.theme.OkGreen

/** Full-screen PIN pad shown over the app while [AppLock.locked]. */
@Composable
fun LockScreen() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    fun submit() { msg = AppLock.tryUnlock(ctx, pin) ?: ""; pin = "" }
    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("REPORT", color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            Text(if (pin.isEmpty()) "Введіть PIN" else "●".repeat(pin.length), fontSize = 26.sp, color = cs.onSurface)
            Text(msg, color = ErrRed, fontSize = 13.sp, modifier = Modifier.height(22.dp))
            Spacer(Modifier.height(12.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
            for (r in rows) Row {
                for (k in r) TextButton(
                    onClick = {
                        when (k) {
                            "⌫" -> pin = pin.dropLast(1)
                            "OK" -> if (pin.length >= 4) submit()
                            else -> if (pin.length < 8) pin += k
                        }
                    },
                    modifier = Modifier.size(84.dp),
                ) { Text(k, fontSize = 26.sp, color = if (k == "OK") OkGreen else cs.onSurface) }
            }
        }
    }
}

/** «БЕЗПЕКА» section for the settings dialog. */
@Composable
fun SecuritySection() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var hasPin by remember { mutableStateOf(AppLock.hasPin(ctx)) }
    var after by remember { mutableStateOf(AppLock.lockAfterMin(ctx)) }
    var setting by remember { mutableStateOf(false) }
    Text("БЕЗПЕКА", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Text(
        if (SecurePrefs.isEncrypted) "Дані на телефоні зашифровані (AES-256, Android Keystore)" else "Шифрування недоступне на цьому пристрої",
        color = if (SecurePrefs.isEncrypted) OkGreen else ErrRed, fontSize = 12.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("PIN-код: " + if (hasPin) "увімкнено" else "вимкнено", Modifier.weight(1f), fontSize = 14.sp)
        TextButton(onClick = { setting = true }) { Text(if (hasPin) "Змінити" else "Встановити") }
        if (hasPin) TextButton(onClick = { AppLock.setPin(ctx, null); hasPin = false }) { Text("Вимкнути", color = ErrRed) }
    }
    if (hasPin) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Блокувати після виходу:", fontSize = 13.sp, modifier = Modifier.weight(1f))
        for ((m, l) in listOf(0 to "одразу", 1 to "1 хв", 5 to "5 хв", 15 to "15 хв")) {
            TextButton(onClick = { after = m; AppLock.setLockAfter(ctx, m) }) {
                Text(l, fontSize = 12.sp, color = if (after == m) OkGreen else cs.onSurfaceVariant)
            }
        }
    }
    if (setting) SetPinDialog({ p -> AppLock.setPin(ctx, p); hasPin = true; setting = false }) { setting = false }
}

@Composable
private fun SetPinDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val ok = a.length in 4..8 && a == b
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PIN-код") },
        text = {
            Column {
                val kb = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                OutlinedTextField(a, { v -> a = v.filter { it.isDigit() }.take(8) }, label = { Text("PIN (4–8 цифр)") },
                    singleLine = true, keyboardOptions = kb, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(b, { v -> b = v.filter { it.isDigit() }.take(8) }, label = { Text("Повторіть") },
                    singleLine = true, keyboardOptions = kb, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text("Без PIN застосунок не відкрити — запам'ятайте його.", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(a) }, enabled = ok) { Text("Зберегти") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}
