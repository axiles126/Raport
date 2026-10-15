package com.example.report.data

import kotlinx.serialization.Serializable

/** Daily reminder alarm at [t] ("HH:mm"). */
@Serializable
data class DailyAlarm(
    val t: String,
    val on: Boolean = false,
)
