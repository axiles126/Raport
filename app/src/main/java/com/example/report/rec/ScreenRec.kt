package com.example.report.rec

import android.content.Context
import android.content.Intent

/** Screen-recording state shared by [RecordService] and the floating button in OverlayService. */
object ScreenRec {
    @Volatile var recording = false
    @Volatile var startedAt = 0L
    /** A projection is alive (Android < 14 reuses it → no consent dialog for the next recording). */
    @Volatile var projectionReady = false
    /** Called on the main thread when [recording] changes. */
    var onChange: (() -> Unit)? = null

    /** Floating-button tap: stop if recording; else start (asks Android for consent when needed). */
    fun toggle(context: Context) {
        when {
            recording -> context.startService(Intent(context, RecordService::class.java).setAction(RecordService.ACTION_STOP))
            projectionReady -> context.startService(Intent(context, RecordService::class.java).setAction(RecordService.ACTION_RECORD))
            else -> context.startActivity(
                Intent(context, RecPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}
