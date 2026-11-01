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
    /** Marked as the one in use on the position (only a note — not in any report). */
    val pos: Boolean = false,
    /** Hidden by «Очистити» (tab/report restart from №1) but still on the position. */
    val cleared: Boolean = false,
    /** Marked damaged («пошкоджений») — counted in the day/night report «Пошкодження БпЛА». */
    val dmg: Boolean = false,
    /** Last local change (ms) — the newer copy wins when devices merge. 0 = older version. */
    val u: Long = 0L,
)
