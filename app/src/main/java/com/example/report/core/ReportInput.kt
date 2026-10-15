package com.example.report.core

import com.example.report.data.DayCfg
import com.example.report.data.Event
import com.example.report.data.Field
import com.example.report.data.Flight

/** Snapshot of everything [ReportBuilder.build] needs. */
data class ReportInput(
    val mode: String,
    val name: String,
    val leader: String,
    val flights: List<Flight>,
    val events: List<Event>,
    val templates: List<String>,
    val fields: List<Field>,
    val dayCfg: DayCfg,
    val shiftStart: String,
    val dayStart: String,
    val shiftCode: String,
    val sortieCount: String,
    val scoutAuto: Boolean,
    val showAir: Boolean,
    val showCountShift: Boolean,
    val now: Long,
    val curRec: Flight?,
    val activeRec: Flight?,
    val flying: Boolean,
    val startTs: Long,
    val dayEnd: String,
    val shiftDetailed: Boolean,
)
