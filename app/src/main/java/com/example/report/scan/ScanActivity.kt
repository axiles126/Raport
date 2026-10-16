package com.example.report.scan

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * QR scanner that follows the phone's rotation (portrait and landscape) and has a torch button.
 * Volume keys also switch the torch (up = on, down = off), as in the stock zxing scanner.
 */
class ScanActivity : CaptureActivity() {
    private var torchOn = false

    override fun initializeContent(): DecoratedBarcodeView {
        val root = FrameLayout(this)
        val scanner = DecoratedBarcodeView(this).apply { id = com.google.zxing.client.android.R.id.zxing_barcode_scanner }
        root.addView(scanner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            val d = resources.displayMetrics.density
            val btn = TextView(this).apply {
                text = "🔦  ЛІХТАРИК"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
                background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(0xAA000000.toInt()); setStroke((1 * d).toInt(), 0x88FFFFFF.toInt()) }
                setOnClickListener {
                    torchOn = !torchOn
                    if (torchOn) scanner.setTorchOn() else scanner.setTorchOff()
                    text = if (torchOn) "🔦  ВИМКНУТИ" else "🔦  ЛІХТАРИК"
                }
            }
            scanner.setTorchListener(object : DecoratedBarcodeView.TorchListener {
                override fun onTorchOn() { torchOn = true; btn.text = "🔦  ВИМКНУТИ" }
                override fun onTorchOff() { torchOn = false; btn.text = "🔦  ЛІХТАРИК" }
            })
            root.addView(btn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (56 * d).toInt()
            })
        }
        setContentView(root)
        return scanner
    }
}
