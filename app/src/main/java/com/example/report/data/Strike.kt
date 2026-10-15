package com.example.report.data

import kotlinx.serialization.Serializable

/** Separate «ураження» report draft (not part of shift reports). [bk]: one munition per line. */
@Serializable
data class StrikeDraft(
    val t: Long = 0L,
    val crew: String = "",
    val pos: String = "",
    val target: String = "ворожий FPV",
    val coords: String = "",
    val bk: String = "",
    val stream: String = "",
    val pilot: String = "",
    val result: String = "Ціль уражено.",
)
