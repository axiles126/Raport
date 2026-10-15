package com.example.report.data

import kotlinx.serialization.Serializable

/** Free-standing shift event (not tied to a flight). [t2] is an optional end time. */
@Serializable
data class Event(
    val t: Long,
    val t2: Long? = null,
    val text: String = "",
    val mgrs: String = "",
)
