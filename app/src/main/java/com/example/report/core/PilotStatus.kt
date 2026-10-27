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
    )

    // TODO(decomp): inlined into PilotWatchService.check(); name reconstructed.
    //  Bytecode checks OFF, then GROUND, then AIR, each overriding the previous → AIR wins.
    fun classify(raw: String?): Kind {
        val t = raw?.lowercase()?.replace('ё', 'е')?.trim() ?: ""
        if (t.isEmpty()) return Kind.UNKNOWN
        if (t == "n/a" || t == "--") return Kind.OFF // no telemetry ≠ unknown mode
        return when {
            AIR.any { t.contains(it) } -> Kind.AIR
            GROUND.any { t.contains(it) } -> Kind.GROUND
            OFF.any { t.contains(it) } -> Kind.OFF
            else -> Kind.UNKNOWN
        }
    }
}
