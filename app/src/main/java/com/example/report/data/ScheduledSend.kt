package com.example.report.data

import kotlinx.serialization.Serializable

/** Scheduled automatic report send. [time] "HH:mm", [mode] report mode, [app] target messenger. */
@Serializable
data class ScheduledSend(
    val id: String,
    val time: String,
    val mode: String,
    val enabled: Boolean = false,
    val app: String = "whatsapp",
    val group: String = "",
)
