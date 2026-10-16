package com.example.report.core

import com.example.report.data.Field
import com.example.report.data.Flight
import com.example.report.data.NamedQty
import java.util.Calendar

/**
 * Builds the text reports.
 *
 * Modes ([ReportInput.mode]): "week", "shift", "day"/"night" ([buildDayNight]);
 * anything else (normally "current") is the single-flight report for [ReportInput.curRec].
 */
object ReportBuilder {
    private const val MIN = 60_000L
    private const val HOUR12 = 43_200_000L
    private const val DAY = 86_400_000L
    private const val WEEK = 604_800_000L

    /** Report line / event with optional end time [t2]. */
    private data class Ev(val t: Long, val t2: Long?, val text: String, val mgrs: String)

    const val POS_BG = "Позиція БГ"
    private val LEAD_HM = Regex("^\\s*(\\d{1,2}):(\\d{2})")

    /** Moments of loss: each "HH:MM Втрата дрону …" comment line, dated relative to the flight's takeoff. */
    fun lossTimes(flights: List<Flight>): List<Long> = flights.flatMap { f ->
        f.c.split("\n").filter { Loss.LOSS_RE.containsMatchIn(it) }.map { line ->
            val m = LEAD_HM.find(line)
            if (m == null) f.lt ?: f.t
            else {
                val cal = java.util.Calendar.getInstance().apply {
                    timeInMillis = f.t
                    set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
                    set(java.util.Calendar.MINUTE, m.groupValues[2].toInt())
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }
                var ts = cal.timeInMillis
                if (ts < f.t - 60_000L) ts += DAY // lost after midnight
                ts
            }
        }
    }

    /** Losses whose moment of loss falls into [from, to) — a loss counts only in its own day/night report. */
    fun lossesIn(flights: List<Flight>, from: Long, to: Long): Int = lossTimes(flights).count { it in from until to }
    private val TIME_RANGE = Regex("^\\s*\\d{1,2}:\\d{2}-\\d{1,2}:\\d{2}")
    private val NUM_PREFIX = Regex("^\\s*\\d+\\s*[.)]\\s*")

    /**
     * Final text right before sending/copying. Day/Night: every line after the "HH:MM-HH:MM date"
     * header is (re)numbered 1..N. Hand-typed lines get numbers and go before "Втрати БпЛА";
     * the fixed tail (Втрати … Позиція БГ) always stays at the end.
     */
    fun finalize(mode: String, text: String): String {
        if (mode != "day" && mode != "night") return text
        val lines = text.split("\n")
        val h = lines.indexOfFirst { TIME_RANGE.containsMatchIn(it) }
        if (h < 0) return text
        val items = lines.drop(h + 1).map { it.replace(NUM_PREFIX, "").trim() }.filter { it.isNotBlank() }
        // Fixed tail always last, in this order; everything else (incl. hand-typed lines) goes before it.
        val tailKeys = listOf("Втрати БпЛА", "Пошкодження БпЛА", "Витрачено пропелерів", "Дронів на позиції", POS_BG)
        fun tailIdx(s: String) = tailKeys.indexOfFirst { s.startsWith(it, ignoreCase = true) }
        val tail = items.filter { tailIdx(it) >= 0 }.sortedBy { tailIdx(it) }
            .let { t -> if (t.any { tailIdx(it) == tailKeys.lastIndex }) t else t + POS_BG }
        val body = items.filter { tailIdx(it) < 0 } + tail
        val numbered = body.filter { tailIdx(it) != tailKeys.lastIndex }.mapIndexed { n, s -> "${n + 1}. $s" }
        return (lines.take(h + 1) + numbered + POS_BG).joinToString("\n")
    }

    fun build(input: ReportInput): String = when (input.mode) {
        "day", "night" -> buildDayNight(input)
        "week" -> buildWeek(input)
        "shift" -> buildShift(input)
        else -> buildCurrent(input)
    }

    // ---------------------------------------------------------------- current flight

    private fun buildCurrent(input: ReportInput): String {
        val out = ArrayList<String>()
        val f = input.curRec
        val showAir = input.showAir

        val rep = f?.rep
        if (rep != null && rep.isNotEmpty()) return rep

        var head = TimeFmt.fmtD(f?.t ?: input.now)
        if (input.name.isNotEmpty()) head = "СП ${input.name} $head"
        out += head

        if (f == null) {
            out += "Ще не було вильотів. Натисни «Взліт»."
            return out.joinToString("\n")
        }

        val mgrs = f.mgrs
        val lt = f.lt
        out += "Взліт: " + TimeFmt.hm(f.t)
        out += "Загальна розвідка"

        val body = cLines(f)
            .map { Coords.TIME_RE.replace(it, "") }
            .filter { it.isNotEmpty() && !Coords.isCoord(it) && it.trim().lowercase() != "загальна розвідка" }
        val lands = body.filter { Coords.isLand(it) }
        val shown = if (lt != null && lands.isNotEmpty()) body.filterNot { Coords.isLand(it) } else body

        for (line in shown) {
            val n = Coords.COUNT_RE.find(line)?.groupValues?.get(1)?.toIntOrNull()
            out += if (n == null) {
                line
            } else {
                Coords.COUNT_RE.replace(line, "").trimEnd() + " - " + n + " " + timesWord(n)
            }
        }

        val coordLine =
            if (mgrs.isNotEmpty() && body.none { it.contains(mgrs) }) "За координатами $mgrs" else null

        if ((lt == null || lands.isEmpty()) && coordLine != null) out += coordLine

        if (lt != null) {
            if (lands.isEmpty()) {
                out += "Сів: " + TimeFmt.hm(lt)
            } else {
                for (l in lands) out += l + ": " + TimeFmt.hm(lt)
                if (coordLine != null) out += coordLine
            }
            if (showAir) out += "У польоті: " + TimeFmt.fmt(lt - f.t)
        } else if (input.flying && f === input.activeRec && showAir) {
            val start = if (input.startTs != 0L) input.startTs else f.t
            out += "У польоті: " + TimeFmt.fmt(input.now - start)
        }
        return out.joinToString("\n")
    }

    /** Ukrainian plural of "раз" (1 раз, 2 рази, 5 разів). */
    private fun timesWord(n: Int): String {
        val d10 = n % 10
        val d100 = n % 100
        return when {
            d10 == 1 && d100 != 11 -> "раз"
            d10 in 2..4 && d100 !in 12..14 -> "рази"
            else -> "разів"
        }
    }

    // ---------------------------------------------------------------- week

    private fun buildWeek(input: ReportInput): String {
        val fields = input.fields
        val since = input.now - WEEK
        val week = input.flights.filter { it.t >= since }

        val out = ArrayList<String>()
        out += if (input.name.isNotEmpty()) "Тиждень: ${input.name}" else "Тиждень"
        out += "Усього вилетів: ${week.size}"

        val byDay = LinkedHashMap<String, MutableList<Flight>>()
        for (f in week) {
            val c = Calendar.getInstance().apply { timeInMillis = f.lt ?: f.t }
            val key = "${c.get(Calendar.YEAR)}-${TimeFmt.pad2(c.get(Calendar.MONTH) + 1)}-${TimeFmt.pad2(c.get(Calendar.DAY_OF_MONTH))}"
            byDay.getOrPut(key) { ArrayList() }.add(f)
        }

        var first = true
        for (key in byDay.keys.sorted()) {
            val list = byDay[key]!!
            val ts = list[0].lt ?: list[0].t
            val c = Calendar.getInstance().apply { timeInMillis = ts }
            if (!first) out += ""
            val dow = arrayOf("нд", "пн", "вт", "ср", "чт", "пт", "сб")[TimeFmt.cal(ts).get(Calendar.DAY_OF_WEEK) - 1]
            out += "${TimeFmt.pad2(c.get(Calendar.DAY_OF_MONTH))}.${TimeFmt.pad2(c.get(Calendar.MONTH) + 1)} ($dow): ${list.size}"

            for (f in list) {
                val from = TimeFmt.hm(f.t)
                val lt = f.lt
                val to = if (lt != null) TimeFmt.hm(lt) else "…"
                val dur = if (lt != null) " (" + TimeFmt.fmt(lt - f.t) + ")" else ""
                val cl = cLines(f)
                val head = "  $from–$to$dur"
                out += if (cl.size <= 1) {
                    head + (if (cl.isEmpty()) "" else " — " + cl[0]) + fieldsSuffix(f, fields)
                } else {
                    (listOf(head + fieldsSuffix(f, fields)) + cl.map { "    · $it" }).joinToString("\n")
                }
            }
            first = false
        }

        val tplCounts = ArrayList<String>()
        for (tpl in input.templates) {
            val key = tpl.lowercase().trim()
            if (key.isEmpty()) continue
            val n = week.count { it.c.lowercase().contains(key) }
            if (n > 0) tplCounts += "$tpl: $n"
        }
        val totals = tplCounts + fieldTotals(week, fields)
        if (totals.isNotEmpty()) {
            out += ""
            out.addAll(totals)
        }
        return out.joinToString("\n")
    }

    // ---------------------------------------------------------------- shift (24h)

    private fun buildShift(input: ReportInput): String {
        val name = input.name
        val scoutAuto = input.scoutAuto
        val code = input.shiftCode
        val (from, to) = shiftBounds(input.now, input.shiftStart)

        val flights = input.flights
            .filter { it.t in from until to }
            .mapNotNull(::stripLoss)
            .filter { it.c.trim().isNotEmpty() || it.mgrs.isNotEmpty() }

        val flightEvs = flights.flatMap { f ->
            if (input.shiftDetailed) {
                flightSegments(f, input.showCountShift)
            } else {
                val lines = cLines(f).map {
                    val s = Coords.TIME_RE.replace(it, "")
                    if (!input.showCountShift) Coords.COUNT_ANY.replace(s, "") else s
                }
                val text = lines.filter { it.isNotEmpty() && !Coords.isCoord(it) }.joinToString(", ")
                val coord = lines.firstOrNull { Coords.isCoord(it) }
                val mgrs = if (Coords.COORD_RE.containsMatchIn(text)) "" else f.mgrs.ifEmpty { coord ?: "" }
                listOf(Ev(f.t, f.lt, text, mgrs))
            }
        }

        val evs = (input.events
            .filter { it.t in from until to }
            .map { Ev(it.t, it.t2, it.text, it.mgrs) } + flightEvs)
            .sortedBy { it.t }

        fun scout(a: Long, b: Long) = TimeFmt.hm(a) + "-" + TimeFmt.hm(b) + "-Ведення розвідки"

        val out = ArrayList<String>()
        if (name.isNotEmpty()) out += "Екіпаж СП \"$name\":"
        out += TimeFmt.fmtD(from) + "-" + TimeFmt.fmtD(to)
        out += "Перелік подій та виявлених цілей" + (if (code.isNotEmpty()) " $code" else "") + " в районі спостереження"

        var cursor = from
        for (ev in evs) {
            if (scoutAuto && ev.t > cursor) out += scout(cursor, ev.t)
            val t2 = ev.t2
            val time = TimeFmt.hm(ev.t) + (if (t2 != null) "-" + TimeFmt.hm(t2) else "")
            out += time +
                (if (ev.text.isNotEmpty()) " " + ev.text else "") +
                (if (ev.mgrs.isNotEmpty()) " за координатами " + ev.mgrs else "")
            cursor = t2 ?: ev.t
        }
        if (scoutAuto && cursor < to) out += scout(cursor, to)

        // sorties of THIS shift window (not a rolling 24 h — that's the counter at the top)
        val sorties = input.sortieCount.trim().toIntOrNull()
            ?: input.flights.count { it.t in from until to }
        out += "За час добової зміни здійснено $sorties вильотів."
        out += "Без втрат чи поранень серед особового складу СП $name: Інформацію про події та виявлені цілі було передано оперативному черговому."
        return out.joinToString("\n")
    }

    /**
     * Drops drone-loss lines from a flight's comment for the shift report. Coordinates are kept only
     * if some meaningful (non-coordinate) text remains; returns null if nothing is left at all.
     */
    // TODO(decomp): inlined into build(); name reconstructed.
    private fun stripLoss(f: Flight): Flight? {
        val lines = f.c.split("\n")
        if (lines.none { Loss.LOSS_RE.containsMatchIn(it) }) return f
        val kept = lines.filterNot { Loss.LOSS_RE.containsMatchIn(it) }
        val hasText = kept.any {
            val s = Coords.TIME_RE.replace(it.trim(), "")
            s.isNotEmpty() && !Coords.isCoord(s)
        }
        val c = kept.joinToString("\n").trim()
        val mgrs = if (hasText) f.mgrs else ""
        return if (c.isEmpty() && mgrs.isEmpty()) null else f.copy(c = c, mgrs = mgrs)
    }

    /**
     * Detailed shift mode: splits a flight's comment into timed segments. Lines within the same
     * minute are merged; a coordinate line attaches to the preceding segment.
     */
    private fun flightSegments(f: Flight, showCount: Boolean): List<Ev> {
        class Mark(val t: Long, val text: String, val coord: Boolean)
        class Seg(val t: Long, var text: String, var mgrs: String)

        val lt = f.lt
        val t0 = f.t

        val marks = cLines(f).map { line ->
            val hm = Coords.TIME_RE.find(line)?.value?.trim()
            var s = Coords.TIME_RE.replace(line, "")
            if (!showCount) s = Coords.COUNT_ANY.replace(s, "")
            val text = s.trim()
            var t = if (hm != null) {
                var x = TimeFmt.tsFromTime(t0, hm)
                if (x < t0 - HOUR12) x += DAY
                if (x > t0 + HOUR12) x -= DAY
                x
            } else {
                t0
            }
            if (t < t0) t = t0
            if (lt != null && t > lt) t = lt
            Mark(t, text, Coords.isCoord(text))
        }.sortedBy { it.t }

        val segs = ArrayList<Seg>()
        for (m in marks) {
            if (m.text.isEmpty()) continue
            val last = segs.lastOrNull()
            if (m.coord) {
                if (last != null && last.mgrs.isEmpty() && !Coords.COORD_RE.containsMatchIn(last.text)) {
                    last.mgrs = m.text
                } else if (last == null) {
                    segs += Seg(m.t, "", m.text)
                }
            } else {
                if (last != null && last.text == m.text) continue
                if (last == null || last.t / MIN != m.t / MIN) {
                    segs += Seg(m.t, m.text, "")
                } else {
                    last.text = if (last.text.isNotEmpty()) last.text + ", " + m.text else m.text
                }
            }
        }

        if (f.mgrs.isNotEmpty() &&
            segs.none { it.mgrs.isNotEmpty() || Coords.COORD_RE.containsMatchIn(it.text) }
        ) {
            if (segs.isEmpty()) segs += Seg(t0, "", f.mgrs) else segs.first().mgrs = f.mgrs
        }

        return segs.mapIndexed { i, s ->
            val end = if (i + 1 < segs.size) segs[i + 1].t else lt
            Ev(s.t, end, s.text, s.mgrs)
        }
    }

    // ---------------------------------------------------------------- day / night

    fun buildDayNight(input: ReportInput): String {
        val mode = input.mode
        val name = input.name
        val cfg = input.dayCfg
        val now = input.now

        var win = dayNightWindow(mode, input.dayStart, input.dayEnd, now)
        if (mode == "night" && win.first > now) {
            win = Pair(win.first - DAY, win.second - DAY)
        }
        val (from, to) = win

        val flights = input.flights.filter { it.t in from until to }.sortedBy { it.t }
        val lines = flights.flatMap { f -> cLines(f).map { Coords.TIME_RE.replace(it, "").lowercase() } }

        /** "×N" suffix multiplies a line. */
        fun lineMult(s: String): Int = Coords.COUNT_RE.find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        fun count(re: Regex): Int = lines.filter { re.containsMatchIn(it) }.sumOf { lineMult(it) }

        val out = ArrayList<String>()
        if (name.isNotEmpty()) out += "Екіпаж СП \"$name\":"

        var date = TimeFmt.fmtD4(from)
        val date2 = TimeFmt.fmtD4(to)
        if (date != date2) date = "$date-$date2"
        out += TimeFmt.hm(from) + "-" + TimeFmt.hm(to) + " " + date

        out += "1. Кількість розвідувальних вильотів - ${flights.size}"
        out += "2. Кількість виявлених цілей противника - " + count(Regex("виявл|ціл"))
        out += "3. Кількість проведених коригувань засобів ураження - " + count(Regex("кориг|корег"))
        out += "4. Супровід СОУ - " + count(Regex("соу|sou", RegexOption.IGNORE_CASE))
        out += "5. Супровід НРК - " + count(Regex("нрк|nrk", RegexOption.IGNORE_CASE))

        val builtIn = Regex("розвід|виявл|ціл|кориг|корег|соу|sou|нрк|nrk", RegexOption.IGNORE_CASE)
        var i = 6
        for (tpl in input.templates) {
            if (builtIn.containsMatchIn(tpl.lowercase())) continue
            val key = tpl.lowercase().trim()
            val n = lines.filter { it.contains(key) }.sumOf { lineMult(it) }
            if (n > 0) {
                out += "$i. $tpl - $n"
                i++
            }
        }
        for (s in fieldTotals(flights, input.fields)) {
            out += "$i. $s"
            i++
        }

        out += "$i. Втрати БпЛА - ${lossesIn(input.flights, from, to) + cfg.lost}"
        out += "${i + 1}. Пошкодження БпЛА - ${cfg.dmg}"
        val props = cfg.props.filter { it.name.isNotEmpty() }
            .ifEmpty { listOf(NamedQty("Тип А"), NamedQty("Тип Б")) }
        out += "${i + 2}. Витрачено пропелерів - " + props.joinToString(",") { "${it.name} - ${it.qty} шт" }
        val drones = (input.posDrones ?: cfg.drones).filter { it.name.isNotEmpty() && it.qty > 0 }
        out += "${i + 3}. Дронів на позиції " +
            if (drones.isNotEmpty()) drones.joinToString(", ") { "\"${it.name}\" ${it.qty} шт" } else "- 0 шт"
        out += POS_BG

        return out.joinToString("\n")
    }

    // ---------------------------------------------------------------- time windows

    /** "HH:mm" → minutes of day, or null if invalid. */
    fun minutesOf(s: String): Int? {
        val p = s.trim().split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** Length of the day period from [start] to [end]; 12h if either is unset/invalid or equal. */
    fun dayLenMs(start: String, end: String): Long {
        val s = minutesOf(start) ?: return HOUR12
        val e = minutesOf(end) ?: return HOUR12
        val diff = ((e - s) % 1440 + 1440) % 1440
        if (diff == 0) return HOUR12
        return diff * MIN
    }

    /** Effective day end "HH:mm" (dayStart + day length). */
    fun dayEndEff(start: String, end: String): String {
        val m = ((minutesOf(start) ?: 360) + (dayLenMs(start, end) / MIN).toInt()) % 1440
        return String.format("%02d:%02d", m / 60, m % 60)
    }

    /**
     * Current day ("day") or night (any other [mode]) window. The day starts at [dayStart]
     * (default 06:00) of today, or of yesterday if that time hasn't come yet.
     */
    fun dayNightWindow(mode: String, dayStart: String, dayEnd: String, now: Long): Pair<Long, Long> {
        val p = dayStart.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: 6
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        val nowCal = Calendar.getInstance().apply { timeInMillis = now }
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE) < h * 60 + m) {
            start.add(Calendar.DAY_OF_MONTH, -1)
        }
        val s = start.timeInMillis
        val len = dayLenMs(dayStart, dayEnd)
        return if (mode == "day") Pair(s, s + len) else Pair(s + len, s + DAY)
    }

    /** 24h shift window starting at [shiftStart] ("HH:mm") that contains [now]. */
    fun shiftBounds(now: Long, shiftStart: String): Pair<Long, Long> {
        val p = shiftStart.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: 0
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (now < c.timeInMillis) c.add(Calendar.DAY_OF_MONTH, -1)
        return Pair(c.timeInMillis, c.timeInMillis + DAY)
    }

    // ---------------------------------------------------------------- helpers

    /** Non-empty trimmed comment lines. */
    private fun cLines(f: Flight): List<String> = f.c.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    /** Totals of custom fields over [flights] (sum for numbers, counts per option/value otherwise). */
    private fun fieldTotals(flights: List<Flight>, fields: List<Field>): List<String> {
        val out = ArrayList<String>()
        for (field in fields) {
            val vals = flights.mapNotNull { (it.x[field.id] ?: "").trim().ifEmpty { null } }
            if (vals.isEmpty()) continue
            val name = field.name
            when (field.type) {
                "number" -> {
                    val sum = vals.sumOf { it.toDoubleOrNull() ?: 0.0 }
                    if (sum > 0.0) {
                        out += "Усього $name: " + if (sum % 1.0 == 0.0) sum.toLong().toString() else sum.toString()
                    }
                }
                "select" -> for (opt in field.opts) {
                    val n = vals.count { it == opt }
                    if (n > 0) out += "$name — $opt: $n"
                }
                else -> {
                    val counts = LinkedHashMap<String, Int>()
                    for (v in vals) counts[v] = (counts[v] ?: 0) + 1
                    for ((k, n) in counts.entries.sortedByDescending { it.value }) out += "$name — $k: $n"
                }
            }
        }
        return out
    }

    /** " · Name: value · …" for filled fields of [f] (zero numbers skipped). */
    private fun fieldsSuffix(f: Flight, fields: List<Field>): String {
        val parts = ArrayList<String>()
        for (field in fields) {
            val v = (f.x[field.id] ?: "").trim()
            if (v.isEmpty()) continue
            if (field.type == "number" && (v.toDoubleOrNull() ?: 0.0) == 0.0) continue
            parts += "${field.name}: $v"
        }
        return if (parts.isNotEmpty()) " · " + parts.joinToString(" · ") else ""
    }
}
