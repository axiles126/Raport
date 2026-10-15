package com.example.report.data

import kotlinx.serialization.Serializable

/** One «Заїхав дрон» scan: time + QR contents. Separate list, never part of the shift reports. */
@Serializable
data class DroneIn(val t: Long, val code: String = "")
