package com.example.report.data

import kotlinx.serialization.Serializable

/**
 * One sortie.
 * [t] takeoff, [lt] landing, [c] comment lines, [mgrs] coordinates,
 * [x] custom field values by [Field.id], [rep] manually edited report text.
 */
@Serializable
data class Flight(
    val t: Long,
    val c: String = "",
    val lt: Long? = null,
    val mgrs: String = "",
    val x: Map<String, String> = LinkedHashMap(),
    val rep: String? = null,
)
