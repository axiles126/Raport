package com.example.report.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * All app data lives in an AES-256 encrypted SharedPreferences file; the key is kept in the
 * Android Keystore (hardware-backed where available), so a copied data folder is unreadable.
 * First start after the update moves the old plain "kapibara" file into it and deletes the plain copy.
 */
object SecurePrefs {
    private const val ENC_FILE = "kapibara_enc"
    private const val PLAIN_FILE = "kapibara"
    @Volatile private var cached: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = context.applicationContext
            val sp = try {
                val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    app, ENC_FILE, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ).also { migrate(app, it) }
            } catch (e: Throwable) {
                // Keystore broken on this device: keep working with the plain file rather than lose data.
                Log.w("SecurePrefs", "encryption unavailable: " + e.javaClass.simpleName)
                app.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
            }
            cached = sp
            return sp
        }
    }

    val isEncrypted: Boolean get() = cached is EncryptedSharedPreferences

    private fun migrate(app: Context, enc: SharedPreferences) {
        val plain = app.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
        val all = plain.all
        if (all.isEmpty()) return
        val e = enc.edit()
        for ((k, v) in all) when (v) {
            is String -> e.putString(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is Set<*> -> @Suppress("UNCHECKED_CAST") e.putStringSet(k, v as Set<String>)
        }
        if (e.commit()) {
            plain.edit().clear().commit()
            app.deleteSharedPreferences(PLAIN_FILE)
        }
    }

    /** Panic wipe: forget the cached instance (files are deleted by the caller). */
    fun reset() { cached = null }
}
