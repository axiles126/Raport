package com.example.report.core

/** Drone loss causes and their report wording. */
object Loss {
    val CAUSES = listOf("shot", "ew", "weather")

    val LOSS_RE = Regex("втрат\\S*\\s+(дрон|бпла|борт)", RegexOption.IGNORE_CASE)

    fun label(cause: String): String = when (cause) {
        "shot" -> "Збиття"
        "ew" -> "Ворожий РЕБ"
        else -> "Погодні умови"
    }

    fun line(cause: String): String = "Втрата дрону " + when (cause) {
        "shot" -> "внаслідок збиття"
        "ew" -> "внаслідок дії ворожого РЕБ"
        else -> "внаслідок погодних умов"
    }
}
