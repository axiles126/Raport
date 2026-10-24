package com.example.report.rec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/**
 * Reads the drone heading («048» above the HSI compass in DJI Pilot 2) from the screen once a
 * second: small crop → upscale → on-device OCR (ML Kit, bundled model). The crop is a fraction
 * of the screen (prefs «kap_hdg_rect» = "x0,y0,x1,y1"), default fits the DJI RC Plus 1920×1200.
 */
class HeadingReader(private val ctx: Context, private val projection: MediaProjection, private val onUpdate: () -> Unit) {
    private val thread = HandlerThread("hdg").apply { start() }
    private val bg = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var busy = false
    private var w = 0
    private var h = 0
    private var candidate: Int? = null

    fun start(): Boolean = try {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        w = dm.widthPixels; h = dm.heightPixels
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = projection.createVirtualDisplay("report-hdg", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, bg)
        bg.postDelayed(tick, 800)
        true
    } catch (e: Exception) {
        ScreenRec.lastRaw = "помилка: " + (e.message ?: e.javaClass.simpleName)
        stop(); false
    }

    fun stop() {
        bg.removeCallbacks(tick)
        runCatching { display?.release() }; display = null
        runCatching { reader?.close() }; reader = null
        runCatching { ocr.close() }
        thread.quitSafely()
    }

    private val tick = object : Runnable {
        override fun run() {
            runCatching { grab() }
            bg.postDelayed(this, 1000)
        }
    }

    private fun rect(): IntArray {
        val def = floatArrayOf(0.455f, 0.70f, 0.545f, 0.785f)
        val s = com.example.report.data.Store(ctx).sp.getString("kap_hdg_rect", "") ?: ""
        val f = s.split(",").mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 4 }?.toFloatArray() ?: def
        val x0 = (f[0] * w).toInt().coerceIn(0, w - 2); val x1 = (f[2] * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (f[1] * h).toInt().coerceIn(0, h - 2); val y1 = (f[3] * h).toInt().coerceIn(y0 + 1, h)
        return intArrayOf(x0, y0, x1, y1)
    }

    private fun grab() {
        if (busy) return
        val img = reader?.acquireLatestImage() ?: return
        val crop: Bitmap
        try {
            val plane = img.planes[0]
            val rowPx = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val (x0, y0, x1, y1) = rect().toList()
            crop = Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0)
            full.recycle()
        } finally { img.close() }
        // ×3 upscale + high-contrast copy (light/green digits → black on white)
        val big = Bitmap.createScaledBitmap(crop, crop.width * 3, crop.height * 3, true)
        val bw = binarize(big)
        runCatching { File(ctx.filesDir, "hdg_crop.png").outputStream().use { big.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        busy = true
        recognize(bw) { v1, raw1 ->
            if (v1 != null) { accept(v1, raw1); busy = false }
            else recognize(big) { v2, raw2 -> accept(v2, raw1 + " | " + raw2); busy = false }
        }
    }

    private fun recognize(b: Bitmap, done: (Int?, String) -> Unit) {
        ocr.process(InputImage.fromBitmap(b, 0))
            .addOnSuccessListener { t -> val raw = t.text.replace("\n", " "); done(parse(raw), raw) }
            .addOnFailureListener { done(null, "ocr: " + it.message) }
    }

    /** First 3-digit group 000..359 (DJI shows the heading zero-padded); O/o → 0. */
    private fun parse(raw: String): Int? {
        val s = raw.replace('O', '0').replace('o', '0').replace('D', '0')
        return Regex("(?<!\\d)(\\d{3})(?!\\d)").findAll(s).mapNotNull { it.value.toIntOrNull() }.firstOrNull { it in 0..359 }
    }

    /** A new value counts after it is read twice in a row (or is close to the last one) — filters OCR misreads. */
    private fun accept(v: Int?, raw: String) {
        ScreenRec.lastRaw = raw.take(60)
        val now = System.currentTimeMillis()
        if (v == null) {
            if (now - ScreenRec.headingAt > 5000) ScreenRec.heading = null
        } else {
            val cur = ScreenRec.heading
            val close = cur != null && minOf((v - cur + 360) % 360, (cur - v + 360) % 360) <= 40
            if (close || candidate == v) { ScreenRec.heading = v; ScreenRec.headingAt = now; candidate = null }
            else candidate = v
        }
        onUpdate()
    }

    private fun binarize(src: Bitmap): Bitmap {
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        for (i in px.indices) {
            val c = px[i]
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val text = (g > 140 && g - r > 50) || (r > 200 && g > 200 && b > 200) // teal/green or white digits
            px[i] = if (text) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888)
    }
}
