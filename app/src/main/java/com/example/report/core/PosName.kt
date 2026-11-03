package com.example.report.core

/**
 * Position name in report headers. If «Назва позиції» starts with a short upper-case type word
 * («СП Дирижабль», «ВОП Ромашка», «ТЗ Дирижабль») that word is used; otherwise the default «ТЗ».
 * Pure — unit-tested.
 */
object PosName {
    const val DEFAULT_TYPE = "ТЗ"
    private val TYPE = Regex("^([A-ZА-ЯІЇЄҐ]{1,5})\\s+(.+)$")

    /** (type, name) — name without surrounding quotes. */
    fun split(raw: String): Pair<String, String> {
        val s = raw.trim()
        val m = TYPE.find(s)
        val (t, n) = if (m != null) m.groupValues[1] to m.groupValues[2] else DEFAULT_TYPE to s
        return t to n.trim().trim('"', '«', '»', '“', '”').trim()
    }

    /** ТЗ "Дирижабль" */
    fun quoted(raw: String): String = split(raw).let { (t, n) -> "$t \"$n\"" }

    /** ТЗ Дирижабль */
    fun plain(raw: String): String = split(raw).let { (t, n) -> "$t $n" }
}
