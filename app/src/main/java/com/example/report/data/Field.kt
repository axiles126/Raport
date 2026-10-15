package com.example.report.data

import kotlinx.serialization.Serializable

/** User-defined per-flight field. [type] is "text", "number" or "select" ([opts] for select). */
@Serializable
data class Field(
    val id: String,
    val name: String,
    val type: String = "text",
    val opts: List<String> = emptyList(),
)
