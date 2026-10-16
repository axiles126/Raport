package com.example.report.core

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle

/** Marks a clip as sensitive: Android 13+ hides it from the clipboard preview / keyboard suggestions. */
fun sensitiveClip(clip: ClipData): ClipData = clip.apply {
    if (Build.VERSION.SDK_INT >= 33) {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
}
