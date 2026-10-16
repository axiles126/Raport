package com.example.report.core

/** Comment-line parsing helpers: time prefixes, MGRS coordinates, repeat counters. */
object Coords {
    /** Leading "HH:mm " time stamp of a comment line. */
    val TIME_RE = Regex("^\\d{1,2}:\\d{2}\\s+")

    /** Line that starts with an MGRS reference. */
    val MGRS_RE = Regex("^\\d{1,2}\\s*[C-X]\\s*[A-Z]{2}\\s*\\d{2,5}\\s*\\d{2,5}\\b", RegexOption.IGNORE_CASE)

    /** Landing-type lines (loss / emergency landing / shot down). */
    val LAND_RE = Regex("втрат\\S*\\s+(дрон|бпла)|аварійн\\S*\\s+посадк|збит\\S*\\s+(дрон|бпла)")

    /** Trailing "за координатами …" clause. */
    val COORD_RE = Regex("\\s*за координатами.*$", RegexOption.IGNORE_CASE)

    /** Trailing repeat counter "×N". */
    val COUNT_RE = Regex("\\s*[×xX](\\d+)\\s*$")

    /** Any repeat counter "×N". */
    val COUNT_ANY = Regex("\\s*[×xX]\\d+\\b")

    val RAW_MGRS = Regex("^(\\d{1,2})([C-X])([A-Z]{2})(\\d+)$")

    val INLINE_COORD = Regex("\\b(\\d{1,2}[C-X])\\s?([A-Z]{2})\\s?(\\d{2,5})\\s?(\\d{2,5})\\b", RegexOption.IGNORE_CASE)

    fun isCoord(s: String): Boolean = MGRS_RE.containsMatchIn(s.trim())

    fun isLand(s: String): Boolean = LAND_RE.containsMatchIn(s.lowercase())

    /** "36UUA12345678" → "36U UA 1234 5678"; returns the input unchanged if it does not parse. */
    fun fmtMGRS(s: String?): String {
        val raw = (s ?: "").replace(Regex("\\s+"), "").uppercase()
        val m = RAW_MGRS.find(raw) ?: return s ?: ""
        val digits = m.groupValues[4]
        if (digits.length % 2 != 0) return s ?: ""
        val half = digits.length / 2
        return m.groupValues[1] + m.groupValues[2] + " " + m.groupValues[3] + " " +
            digits.substring(0, half) + " " + digits.substring(half)
    }

    /**
     * Appends template [line] to comment [text]. A repeated (non-coordinate, non-landing) line
     * increments its "×N" counter instead of being added again; new lines get an "HH:mm " prefix
     * (coordinates are added as-is).
     */
    fun addTemplateLine(now: Long, text: String, line: String): String {
        val cur = text.trimEnd()
        var add = line.trim()
        if (!isCoord(add) && !isLand(add)) {
            val lines = cur.split("\n").toMutableList()
            val idx = lines.indexOfFirst {
                COORD_RE.replace(COUNT_RE.replace(TIME_RE.replace(it.trim(), ""), ""), "")
                    .trim()
                    .equals(add, ignoreCase = true)
            }
            // A repeat of a template that already got its own coordinates is a new target → new line.
            if (idx >= 0 && !COORD_RE.containsMatchIn(lines[idx])) {
                val existing = lines[idx]
                val n = COUNT_RE.find(existing)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                lines[idx] = COUNT_RE.replace(existing, "").trimEnd() + " ×" + (n + 1)
                return lines.joinToString("\n")
            }
        }
        if (!isCoord(add)) add = TimeFmt.hm(now) + " " + add
        return if (cur.isEmpty()) add else cur + "\n" + add
    }

    /**
     * Attaches [mgrs] to comment line [idx]. If that line is a merged "×N" (N > 1) without coordinates,
     * one occurrence is split off into its own line with these coordinates and the rest stays "×(N-1)" —
     * so several targets of the same template each get their own coordinates.
     */
    fun withCoords(lines: List<String>, idx: Int, mgrs: String): List<String> {
        if (idx !in lines.indices) return lines
        val line = lines[idx]
        val n = COUNT_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val out = lines.toMutableList()
        if (n > 1 && !COORD_RE.containsMatchIn(line)) {
            val base = COUNT_RE.replace(line, "").trimEnd()
            out[idx] = if (n - 1 > 1) "$base ×${n - 1}" else base
            out.add(idx, "$base за координатами $mgrs") // split-off goes before the rest → chronological order
        } else {
            out[idx] = COORD_RE.replace(line, "").trimEnd() + " за координатами " + mgrs
        }
        return out
    }
}
