package com.example.report.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.report.R
import com.example.report.core.ReportBuilder
import com.example.report.core.ReportInput
import com.example.report.data.DayCfg
import com.example.report.data.Event
import com.example.report.data.Field
import com.example.report.data.Flight
import com.example.report.data.ScheduledSend
import com.example.report.data.Store
import java.util.Calendar

/**
 * Fires a scheduled "send report to group" reminder: builds the report text, posts a
 * notification whose tap opens WhatsApp/Signal share with that text, then re-arms the alarm
 * for the next day.
 */
class ReportAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val mode = intent.getStringExtra("mode") ?: "night"
        val app = intent.getStringExtra("app") ?: "whatsapp"
        val group = intent.getStringExtra("group") ?: ""

        val text = runCatching { buildText(context, mode) }
            .onFailure { Log.w(TAG, "build failed: " + it.message) }
            .getOrDefault("Звіт недоступний")
        Log.i(TAG, "fire id=$id mode=$mode app=$app textLen=${text.length}")

        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Авто-звіти", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Нагадування надіслати звіт у групу за розкладом"
            nm.createNotificationChannel(ch)
        }

        val pkg = if (app == "signal") "org.thoughtcrime.securesms" else "com.whatsapp"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context, id.hashCode(), send,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (mode) {
            "day" -> "День"
            "night" -> "Ніч"
            "shift" -> "Доба"
            else -> "Звіт"
        }
        val appName = if (app == "signal") "Signal" else "WhatsApp"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Звіт «$title» готовий")
            .setContentText("Надіслати у " + appName + (if (group.isNotBlank()) " · $group" else ""))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
            Log.i(TAG, "notified id=$id")
        } catch (e: Exception) { // SecurityException without POST_NOTIFICATIONS
            Log.w(TAG, "notify failed: " + e.message)
        }

        // Re-arm for tomorrow, preferring the persisted (possibly edited) entry.
        val fallback = ScheduledSend(id, intent.getStringExtra("time") ?: "05:50", mode, true, app, group)
        val stored = Store(context).load<List<ScheduledSend>>("kap_schedules", emptyList())
            .firstOrNull { it.id == id }
        schedule(context, stored ?: fallback)
    }

    companion object {
        private const val TAG = "ReportSched"
        private const val CHANNEL_ID = "report_schedule"

        private fun pending(context: Context, s: ScheduledSend): PendingIntent {
            val intent = Intent(context, ReportAlarmReceiver::class.java).apply {
                action = "com.example.report.SCHED_" + s.id
                putExtra("id", s.id)
                putExtra("mode", s.mode)
                putExtra("app", s.app)
                putExtra("group", s.group)
                putExtra("time", s.time)
            }
            return PendingIntent.getBroadcast(
                context, s.id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Arms (or cancels, if disabled) the next daily occurrence of [s]. */
        fun schedule(context: Context, s: ScheduledSend) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!s.enabled) {
                am.cancel(pending(context, s))
                return
            }
            val pi = pending(context, s)
            val now = System.currentTimeMillis()
            val parts = s.time.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
            }
            val at = cal.timeInMillis
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    return
                }
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }

        /** Without USE_EXACT_ALARM, Android 14+ denies exact alarms by default — open the toggle once. */
        fun ensureExactAllowed(context: Context) {
            if (Build.VERSION.SDK_INT < 31) return
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (am.canScheduleExactAlarms()) return
            try {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
            }
        }

        fun rescheduleAll(context: Context) {
            Store(context).load<List<ScheduledSend>>("kap_schedules", emptyList())
                .forEach { schedule(context, it) }
        }

        /**
         * Builds the report text for [mode] straight from persisted state (no ViewModel).
         * TODO(decomp): original location/name unknown (merged into io.ktor.util.CryptoKt.build).
         */
        fun buildText(context: Context, mode: String): String {
            val store = Store(context)
            val sp = store.sp
            val flights = store.load<List<Flight>>("kap_flights", emptyList())
            val events = store.load<List<Event>>("kap_events", emptyList())
            val templates = store.load<List<String>>("kap_templates", emptyList())
            val fields = store.load<List<Field>>("kap_fields", emptyList())
            val dayCfg = store.load<DayCfg>("kap_daycfg", DayCfg())
            return ReportBuilder.build(
                ReportInput(
                    mode = mode.ifBlank { "current" },
                    name = sp.getString("kap_name", "") ?: "",
                    leader = sp.getString("kap_leader", "") ?: "",
                    flights = flights,
                    events = events,
                    templates = templates,
                    fields = fields,
                    dayCfg = dayCfg,
                    shiftStart = sp.getString("kap_shiftstart", "16:00") ?: "16:00",
                    dayStart = sp.getString("kap_daystart", "06:00") ?: "06:00",
                    shiftCode = sp.getString("kap_shiftcode", "") ?: "",
                    sortieCount = sp.getString("kap_sorties", "") ?: "",
                    scoutAuto = sp.getBoolean("kap_scout", true),
                    showAir = false,
                    showCountShift = false,
                    now = System.currentTimeMillis(),
                    curRec = flights.lastOrNull(),
                    activeRec = null,
                    flying = false,
                    startTs = 0L,
                    dayEnd = sp.getString("kap_dayend", "") ?: "",
                    shiftDetailed = sp.getBoolean("kap_shiftdetail", true),
                ),
            )
        }
    }
}
