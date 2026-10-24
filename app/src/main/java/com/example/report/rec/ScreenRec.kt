package com.example.report.rec

import android.content.Context
import android.content.Intent

/** Screen-capture state shared by [RecordService] and the floating buttons in OverlayService. */
object ScreenRec {
    @Volatile var recording = false
    @Volatile var startedAt = 0L
    /** A projection is alive (Android < 14 reuses it → no consent dialog next time). */
    @Volatile var projectionReady = false
    /** Called on the main thread when recording / heading state changes. */
    var onChange: (() -> Unit)? = null

    // ---- heading (DJI compass digits) ----
    @Volatile var headingOn = false
    /** Last recognised drone heading 0..359 (null = not read yet / lost). */
    @Volatile var heading: Int? = null
    @Volatile var headingAt = 0L
    @Volatile var lastRaw = ""

    const val PURPOSE_REC = "rec"
    const val PURPOSE_HDG = "hdg"

    private fun svc(context: Context, action: String) =
        context.startService(Intent(context, RecordService::class.java).setAction(action))

    private fun consent(context: Context, purpose: String) = context.startActivity(
        Intent(context, RecPermissionActivity::class.java)
            .putExtra(RecordService.EXTRA_PURPOSE, purpose)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
    )

    /** Floating «●» tap: stop if recording; else start (asks Android for consent when needed). */
    fun toggle(context: Context) {
        when {
            recording -> svc(context, RecordService.ACTION_STOP)
            projectionReady -> svc(context, RecordService.ACTION_RECORD)
            else -> consent(context, PURPOSE_REC)
        }
    }

    /** Start reading the heading from the DJI screen. */
    fun headingStart(context: Context) {
        if (headingOn) return
        if (projectionReady) svc(context, RecordService.ACTION_HDG_ON) else consent(context, PURPOSE_HDG)
    }

    fun headingStop(context: Context) {
        if (headingOn) svc(context, RecordService.ACTION_HDG_OFF)
    }
}
