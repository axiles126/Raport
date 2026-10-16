package com.example.report.core

/** Drone loss causes and their report wording. */
object Loss {
    val CAUSES = listOf("shot", "ew", "weather")

    /** Operator-typed cause: stored as "custom:<text>". */
    const val CUSTOM = "custom:"

    fun isCustom(cause: String) = cause.startsWith(CUSTOM)
    fun customText(cause: String) = if (isCustom(cause)) cause.removePrefix(CUSTOM) else ""
    fun isValid(cause: String) = cause in CAUSES || isCustom(cause)

    val LOSS_RE = Regex("втрат\\S*\\s+(дрон|бпла|борт)", RegexOption.IGNORE_CASE)

    fun label(cause: String): String = when {
        cause == "shot" -> "Збиття"
        cause == "ew" -> "Ворожий РЕБ"
        isCustom(cause) -> "Своя причина"
        else -> "Погодні умови"
    }

    fun line(cause: String): String = when {
        isCustom(cause) -> customText(cause).trim().replace("\n", " ").let { t ->
            when {
                t.isEmpty() -> "Втрата дрону"
                t.startsWith("внаслідок", true) || t.startsWith("через", true) -> "Втрата дрону $t"
                else -> "Втрата дрону внаслідок $t"
            }
        }
        cause == "shot" -> "Втрата дрону внаслідок збиття"
        cause == "ew" -> "Втрата дрону внаслідок дії ворожого РЕБ"
        else -> "Втрата дрону внаслідок погодних умов"
    }
}
