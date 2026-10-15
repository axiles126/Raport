package com.example.report.data

import kotlinx.serialization.Serializable

/** Saved map route: [pts] as (lat, lng); [color] "#rrggbb". */
@Serializable
data class Route(
    val id: String,
    val name: String,
    val color: String = "#f1c40f",
    val pts: List<Pair<Double, Double>> = emptyList(),
    val visible: Boolean = true,
)
