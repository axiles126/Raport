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
    private var startedInProcess = false
    private const val K_FAILS = "kap_pin_fails"
    private const val K_BLOCK = "kap_pin_block" // wall-clock ms; survives restarts
    private const val K_SECURE = "kap_flag_secure"

    fun hasPin(ctx: Context) = Store(ctx).str(K_PIN).isNotEmpty()
    fun lockAfterMin(ctx: Context) = Store(ctx).sp.getInt(K_AFTER, 1)
    fun setLockAfter(ctx: Context, min: Int) = Store(ctx).sp.edit().putInt(K_AFTER, min).apply()

    fun setPin(ctx: Context, pin: String?) {
        Store(ctx).putStringNow(K_PIN, pin?.let { hash(it) } ?: "")
        if (pin == null) locked = false
    }

    /** Call from every Activity.onCreate: locks once per process (also after the system killed it in background). */
    fun onAppStart(ctx: Context) {
        if (startedInProcess) return
        startedInProcess = true
        if (hasPin(ctx)) locked = true
    }

    /** Blocks screenshots / screen recording / recents preview of the app window (default on). */
    fun secureScreen(ctx: Context) = Store(ctx).bool(K_SECURE, true)
    fun setSecureScreen(ctx: Context, on: Boolean) = Store(ctx).putBool(K_SECURE, on)

    /** True if [pin] is the current PIN (counts as an attempt). */
    fun check(ctx: Context, pin: String): String? = attempt(ctx, pin)
    fun onBackground() { backgroundAt = SystemClock.elapsedRealtime() }
    fun onForeground(ctx: Context) {
        if (!hasPin(ctx) || backgroundAt == 0L) return
        if (SystemClock.elapsedRealtime() - backgroundAt >= lockAfterMin(ctx) * 60_000L) locked = true
    }

    /** null = ok; otherwise a message for the lock screen. */
    fun tryUnlock(ctx: Context, pin: String): String? = attempt(ctx, pin).also { if (it == null) locked = false }

    /** Failed attempts and the lockout are persisted, so killing the app does not reset them. */
    private fun attempt(ctx: Context, pin: String): String? {
        val st = Store(ctx)
        val now = System.currentTimeMillis()
        val until = st.sp.getLong(K_BLOCK, 0L)
        if (now < until && until - now < 3_600_000L) return "Зачекайте ${(until - now) / 1000 + 1} с"
        if (verify(st.str(K_PIN), pin)) { st.sp.edit().putInt(K_FAILS, 0).putLong(K_BLOCK, 0L).commit(); return null }
        val fails = st.sp.getInt(K_FAILS, 0) + 1
        val e = st.sp.edit().putInt(K_FAILS, fails)
        if (fails % 5 == 0) e.putLong(K_BLOCK, now + 30_000L * minOf(fails / 5, 60))
        e.commit()
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
