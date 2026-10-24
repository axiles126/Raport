package com.example.report.rec

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Invisible activity: shows Android's «Start recording?» dialog and hands the token to [RecordService]. */
class RecPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        }
    }

    @Deprecated("Activity result API not needed for a one-shot invisible activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, RecordService::class.java)
                    .setAction(RecordService.ACTION_START)
                    .putExtra(RecordService.EXTRA_CODE, resultCode)
                    .putExtra(RecordService.EXTRA_DATA, data)
                    .putExtra(RecordService.EXTRA_PURPOSE, intent.getStringExtra(RecordService.EXTRA_PURPOSE) ?: ScreenRec.PURPOSE_REC),
            )
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object { const val REQ = 7101 }
}
