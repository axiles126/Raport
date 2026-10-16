package com.example.report.core

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.report.data.Store
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN lock for the app UI (background services — floating buttons, DJI watcher — keep working).
 * The PIN is stored only as a salted PBKDF2 hash inside the encrypted store.
 */
object AppLock {
    private const val K_PIN = "kap_pin"
    private const val K_AFTER = "kap_lock_after" // minutes; 0 = immediately
    private const val ITER = 60_000

    /** UI shows the lock screen while true. */
    var locked by mutableStateOf(false)
    private var backgroundAt = 0L
    private var fails = 0
    private var blockedUntil = 0L

    fun hasPin(ctx: Context) = Store(ctx).str(K_PIN).isNotEmpty()
    fun lockAfterMin(ctx: Context) = Store(ctx).sp.getInt(K_AFTER, 1)
    fun setLockAfter(ctx: Context, min: Int) = Store(ctx).sp.edit().putInt(K_AFTER, min).apply()

    fun setPin(ctx: Context, pin: String?) {
        Store(ctx).putStringNow(K_PIN, pin?.let { hash(it) } ?: "")
        if (pin == null) locked = false
    }

    /** Called once on app start. */
    fun onAppStart(ctx: Context) { if (hasPin(ctx)) locked = true }
    fun onBackground() { backgroundAt = SystemClock.elapsedRealtime() }
    fun onForeground(ctx: Context) {
        if (!hasPin(ctx) || backgroundAt == 0L) return
        if (SystemClock.elapsedRealtime() - backgroundAt >= lockAfterMin(ctx) * 60_000L) locked = true
    }

    /** null = ok; otherwise a message for the lock screen. */
    fun tryUnlock(ctx: Context, pin: String): String? {
        val now = SystemClock.elapsedRealtime()
        if (now < blockedUntil) return "Зачекайте ${(blockedUntil - now) / 1000 + 1} с"
        if (verify(Store(ctx).str(K_PIN), pin)) { fails = 0; locked = false; return null }
        fails++
        if (fails % 5 == 0) blockedUntil = now + 30_000L * (fails / 5)
        return "Невірний PIN"
    }

    private fun hash(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val b64 = Base64.getEncoder()
        return b64.encodeToString(salt) + ":" + b64.encodeToString(pbkdf2(pin, salt))
    }

    private fun verify(stored: String, pin: String): Boolean {
        val p = stored.split(":"); if (p.size != 2) return false
        val d = Base64.getDecoder()
        return java.security.MessageDigest.isEqual(pbkdf2(pin, d.decode(p[0])), d.decode(p[1]))
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITER, 256)).encoded
}
