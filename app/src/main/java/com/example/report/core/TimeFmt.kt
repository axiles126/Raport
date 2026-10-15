package com.example.report.core

import java.util.Calendar
import java.util.Locale

object TimeFmt {
    // TODO(decomp): initialised in <clinit> but never read in the shipped build.
    @Suppress("unused")
    private val UK = Locale("uk", "UA")

    fun cal(ts: Long): Calendar = Calendar.getInstance().apply { timeInMillis = ts }

    fun pad2(n: Int): String = n.toString().padStart(2, '0')

    /** Duration as `[h:]mm:ss`. */
    fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = (s / 3600).toInt()
        val m = ((s % 3600) / 60).toInt()
        val sec = (s % 60).toInt()
        return (if (h > 0) "$h:" else "") + pad2(m) + ":" + pad2(sec)
    }

    /** `HH:mm` */
    fun hm(ts: Long): String {
        val c = cal(ts)
        return pad2(c.get(Calendar.HOUR_OF_DAY)) + ":" + pad2(c.get(Calendar.MINUTE))
    }

    /** `dd.MM.yy` */
    fun fmtD(ts: Long): String {
        val c = cal(ts)
        return pad2(c.get(Calendar.DAY_OF_MONTH)) + "." + pad2(c.get(Calendar.MONTH) + 1) + "." +
            (c.get(Calendar.YEAR) % 100).toString().padStart(2, '0')
    }

    /** `dd.MM.yyyy` */
    fun fmtD4(ts: Long): String {
        val c = cal(ts)
        return pad2(c.get(Calendar.DAY_OF_MONTH)) + "." + pad2(c.get(Calendar.MONTH) + 1) + "." + c.get(Calendar.YEAR)
    }

    /** `dd.MM.yy, HH:mm:ss` */
    fun clockAt(ts: Long): String {
        val c = cal(ts)
        return "${fmtD(ts)}, ${pad2(c.get(Calendar.HOUR_OF_DAY))}:${pad2(c.get(Calendar.MINUTE))}:${pad2(c.get(Calendar.SECOND))}"
    }

    /** Normalises user input like "9.05", "09-05", "0905" to "09:05"; null if invalid. */
    fun normHm(s: String): String? {
        val t = s.trim()
        val m = Regex("^(\\d{1,2})[:.,\\- ](\\d{2})$").find(t)
            ?: Regex("^(\\d{2})(\\d{2})$").find(t)
            ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h !in 0..23 || mi !in 0..59) return null
        return pad2(h) + ":" + pad2(mi)
    }

    /** Timestamp on the same calendar day as [base] at time [hm] ("HH:mm"). */
    fun tsFromTime(base: Long, hm: String): Long {
        val p = hm.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: 0
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        return cal(base).apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
