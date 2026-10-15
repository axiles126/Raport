package com.example.report.data

import kotlinx.serialization.Serializable

@Serializable
data class NamedQty(
    val name: String = "",
    val qty: Int = 0,
)
