package com.example.report.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SharedPreferences ("kapibara") + JSON persistence.
 *
 * Keys seen in the app (default in parentheses):
 *  JSON:    kap_flights, kap_templates, kap_events, kap_fields, kap_points, kap_daycfg,
 *           kap_schedules, kap_almdaily, kap_autodji_log  ("" = absent)
 *  String:  kap_name, kap_leader, kap_reportmode ("current"), kap_shiftstart ("16:00"),
 *           kap_daystart ("06:00"), kap_dayend, kap_shiftcode, kap_sorties, kap_basemap ("sat"),
 *           kap_theme ("system"), kap_waphone, kap_needs, kap_netmode ("hub"/"solo"/"client"),
 *           kap_netcode, kap_hubcode, kap_hubip, kap_autodji_last, kap_loss_pilot,
 *           kap_loss_drone ("3МБ")
 *  Boolean: kap_showair (false), kap_bigedit (false), kap_countshift (false), kap_shiftdetail (true),
 *           kap_scout (true), kap_overlay (false), kap_autosend (false), kap_wa (false),
 *           kap_signal (false), kap_needs_num (true), kap_tplfab (true), kap_tplfab_right (true),
 *           kap_autodji (true)
 *  Long:    kap_alarm (0), kap_almlast, kap_tplfab_y
 *  Other:   kap_wxcache (weather cache, owned by ui)
 */
class Store(context: Context) {
    /** Encrypted (see [SecurePrefs]); same keys as the original plain "kapibara" file. */
    val sp: SharedPreferences = SecurePrefs.get(context)

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun putBool(key: String, value: Boolean) {
        sp.edit().putBoolean(key, value).apply()
    }

    fun putString(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }

    /** Synchronous write (commit) — used for data that must survive process death. */
    fun putStringNow(key: String, value: String) {
        sp.edit().putString(key, value).commit()
    }

    // TODO(decomp): the helpers below were fully inlined at every call site
    //  (`sp.getString(k, "") ?: ""`, then `json.decodeFromString(...)` in try/catch falling back
    //  to a default); names are reconstructed.
    fun str(key: String, def: String = ""): String = sp.getString(key, def) ?: def

    fun bool(key: String, def: Boolean = false): Boolean = sp.getBoolean(key, def)

    inline fun <reified T> load(key: String, def: T): T {
        val s = str(key)
        if (s.isEmpty()) return def
        return try {
            json.decodeFromString<T>(s)
        } catch (e: Exception) {
            def
        }
    }

    inline fun <reified T> saveNow(key: String, value: T) {
        putStringNow(key, json.encodeToString(value))
    }
}
