package com.example.report.rec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.report.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen recorder (MediaProjection → MediaRecorder, H.264, no audio) → Movies/Report (mp4).
 * On Android < 14 the projection is kept between recordings so the consent dialog shows once.
 */
class RecordService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var usedOnce = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground("Запис екрана")
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val p = data?.let { runCatching { mpm.getMediaProjection(code, it) }.getOrNull() }
                if (p == null) { fail("Немає дозволу на запис екрана"); return START_NOT_STICKY }
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { main.post { stopRecording(); releaseProjection() } }
                }, main)
                projection = p
                usedOnce = false
                ScreenRec.projectionReady = true
                startRecording()
            }
            ACTION_RECORD -> if (projection != null && !ScreenRec.recording) startRecording() else ScreenRec.toggle(this)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Запис екрана", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(text).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NID, n)
    }

    private fun startRecording() {
        val p = projection ?: return
        if (ScreenRec.recording) return
        // Android 14+: one virtual display per projection → a new consent for every recording
        if (usedOnce && Build.VERSION.SDK_INT >= 34) { releaseProjection(); ScreenRec.toggle(this); return }
        try {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            var w = dm.widthPixels; var h = dm.heightPixels
            val scale = minOf(1.0, 1920.0 / maxOf(w, h))
            w = ((w * scale).toInt() / 16) * 16; h = ((h * scale).toInt() / 16) * 16

            val name = "Report_" + SimpleDateFormat("dd.MM_HH-mm-ss", Locale.US).format(Date()) + ".mp4"
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Report")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: error("MediaStore")
            uri = u
            val fd = contentResolver.openFileDescriptor(u, "w") ?: error("fd")
            pfd = fd
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(w, h)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(if (maxOf(w, h) >= 1600) 8_000_000 else 5_000_000)
            r.setOutputFile(fd.fileDescriptor)
            r.prepare()
            recorder = r
            display = p.createVirtualDisplay("report-rec", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
            usedOnce = true
            r.start()
            ScreenRec.recording = true
            ScreenRec.startedAt = System.currentTimeMillis()
            ScreenRec.onChange?.invoke()
        } catch (e: Exception) {
            cleanupRecorder(delete = true)
            fail("Запис не вдався: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun stopRecording() {
        if (!ScreenRec.recording) return
        ScreenRec.recording = false
        var ok = true
        runCatching { recorder?.stop() }.onFailure { ok = false } // too short → no frames
        cleanupRecorder(delete = !ok)
        ScreenRec.onChange?.invoke()
        Toast.makeText(this, if (ok) "Запис збережено: Відео/Movies → Report" else "Запис надто короткий — не збережено", Toast.LENGTH_SHORT).show()
        if (Build.VERSION.SDK_INT >= 34) { releaseProjection() }
    }

    private fun cleanupRecorder(delete: Boolean) {
        runCatching { recorder?.reset(); recorder?.release() }
        recorder = null
        runCatching { display?.release() }
        display = null
        runCatching { pfd?.close() }
        pfd = null
        uri?.let { u ->
            if (delete) runCatching { contentResolver.delete(u, null, null) }
            else runCatching { contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
        }
        uri = null
    }

    private fun releaseProjection() {
        runCatching { projection?.stop() }
        projection = null
        ScreenRec.projectionReady = false
        ScreenRec.onChange?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        if (projection == null) { ScreenRec.projectionReady = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onDestroy() {
        if (ScreenRec.recording) stopRecording()
        runCatching { projection?.stop() }
        projection = null
        ScreenRec.projectionReady = false
        ScreenRec.recording = false
        ScreenRec.onChange?.invoke()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "rec.start"
        const val ACTION_RECORD = "rec.record"
        const val ACTION_STOP = "rec.stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CH = "screen_rec"
        private const val NID = 43
    }
}
