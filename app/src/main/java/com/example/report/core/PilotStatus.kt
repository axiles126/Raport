package com.example.report.core

/** Classifies the DJI Pilot "flight mode" widget text (RU/EN UI strings). */
object PilotStatus {
    enum class Kind { AIR, GROUND, OFF, UNKNOWN }

    val OFF = listOf("соединение с дроном прервано", "aircraft disconnected")

    val GROUND = listOf(
        "готов", "ready to",
        "подготовка к взлету", "preparing to take off",
        "невозможно выполнить взлет", "unable to take off",
        "не удалось взлететь", "cannot take off",
        "время ожидания", "standby",
    )

    val AIR = listOf(
        "в полете", "in flight", "in-flight",
        "ручное управление", "manual flight",
        "автовзлет", "auto takeoff",
        "выполняется маршрут", "executing flight route",
        "возврат домой", "возвращение домой", "returning to home", "returning home",
        "посадка", "приземление", "landing",
        "точка интереса", "poi", "activetrack",
        "панорамирование", "pano in progress",
        "следование линии", "power line follow",
        "автоматическое уклонение", "auto avoidance", "apas",
        "поиск объекта", "searching for subject",
        // M4T / Pilot 2 in flight: «Режим N - Визуальное позиционирование», «Режим S - GPS» …
        "позиционирование", "positioning", "атти", "atti",
    )

    /** «Режим N - …» / «Mode N - …»: a flight mode prefix; anything after it that is not a ground phrase = flying. */
    private val MODE_PREFIX = Regex("^(режим|mode)\\s+\\S+\\s*[-–—]\\s*\\S")

    /** AIR phrases win over ground ones; the «Режим X - …» prefix is the last resort (after ground phrases). */
    fun classify(raw: String?): Kind {
        val t = raw?.lowercase()?.replace('ё', 'е')?.trim() ?: ""
        if (t.isEmpty()) return Kind.UNKNOWN
        if (t == "n/a" || t == "--") return Kind.OFF // no telemetry ≠ unknown mode
        return when {
            AIR.any { t.contains(it) } -> Kind.AIR
            GROUND.any { t.contains(it) } -> Kind.GROUND
            OFF.any { t.contains(it) } -> Kind.OFF
            MODE_PREFIX.containsMatchIn(t) -> Kind.AIR
            else -> Kind.UNKNOWN
        }
    }
}
