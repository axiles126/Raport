package com.example.report.data

import kotlinx.serialization.Serializable

/** Map marker. */
@Serializable
data class Point(
    val id: String,
    val lat: Double,
    val lng: Double,
    val name: String = "",
    val color: String = "#c0392b",
    val mgrs: String = "",
)
