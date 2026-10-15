package com.example.report.data

import kotlinx.serialization.Serializable

/**
 * One drone entry for the separate «Заїхав/Переданий дрон» reports (never part of shift reports).
 * Line format: "N. <model> (<tail>) <code>". [kind]: "in" = заїхав, "out" = переданий.
 */
@Serializable
data class DroneIn(
    val t: Long,
    val code: String = "",
    val model: String = "Matrice 4T",
    val tail: String = "",
    val kind: String = "in",
)
