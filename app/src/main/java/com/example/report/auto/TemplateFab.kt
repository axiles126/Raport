package com.example.report.auto

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.report.R
import com.example.report.core.TimeFmt
import com.example.report.data.Store
import com.example.report.overlay.FlightBridge
import kotlin.math.abs

/**
 * Edge-docked "templates" tab drawn as an accessibility overlay over DJI Pilot 2.
 * Tap → panel with the user's quick templates; tapping one appends it to the active flight.
 * Drag → move; on release it snaps to the nearest screen edge and remembers side/Y.
 */
class TemplateFab(private val svc: PilotWatchService) {

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val store = Store(svc)
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { closePanel() }

    private var tab: ImageView? = null
    private lateinit var tabLp: WindowManager.LayoutParams
    private var panel: LinearLayout? = null
    private var panelClosedAt = 0L

    private val dm: DisplayMetrics get() = svc.resources.displayMetrics

    private fun px(dp: Int): Int = (dp * dm.density).toInt()

    private fun bg(radiusDp: Int) = GradientDrawable().apply {
        setColor(BG)
        cornerRadius = px(radiusDp).toFloat()
        setStroke(px(1), STROKE)
    }

    fun update(show: Boolean) {
        if (!show) {
            closePanel()
            tab?.let { runCatching { wm.removeView(it) } }
            tab = null
            return
        }
        if (tab != null) return

        val iv = ImageView(svc).apply {
            setImageResource(R.drawable.ic_templates_fab)
            setPadding(px(8), px(14), px(8), px(14))
            background = bg(10)
            alpha = 0.85f
            contentDescription = "Шаблони"
            measure(0, 0)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (store.sp.getBoolean("kap_tplfab_right", true)) dm.widthPixels - iv.measuredWidth else 0
            val savedY = store.sp.getLong("kap_tplfab_y", (dm.heightPixels * 0.55).toLong()).toInt()
            y = savedY.coerceIn(0, (dm.heightPixels - iv.measuredHeight).coerceAtLeast(0))
        }
        tabLp = lp
        iv.setOnTouchListener(DragListener(iv))
        if (runCatching { wm.addView(iv, tabLp) }.isFailure) return
        tab = iv
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class DragListener(private val v: View) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var pressed = false
        private var dragging = false

        override fun onTouch(view: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = tabLp.x; startY = tabLp.y
                    pressed = true; dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pressed) {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (!dragging && (abs(dx) > 12 || abs(dy) > 12)) {
                            dragging = true
                            closePanel()
                        }
                        if (dragging) {
                            tabLp.x = startX + dx
                            tabLp.y = startY + dy
                            runCatching { wm.updateViewLayout(v, tabLp) }
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!pressed) return true
                    pressed = false
                    if (dragging) {
                        val right = tabLp.x + v.width / 2 > dm.widthPixels / 2
                        tabLp.x = if (right) dm.widthPixels - v.width else 0
                        tabLp.y = tabLp.y.coerceIn(0, (dm.heightPixels - v.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, tabLp) }
                        store.putBool("kap_tplfab_right", right)
                        store.sp.edit().putLong("kap_tplfab_y", tabLp.y.toLong()).apply()
                    } else if (System.currentTimeMillis() - panelClosedAt > 400) {
                        if (panel != null) closePanel() else openPanel()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    return true
                }
                else -> return false
            }
        }
    }

    // TODO(decomp): openPanel() is inlined into the tab's touch listener in the APK.
    @SuppressLint("ClickableViewAccessibility")
    private fun openPanel() {
        val tab = tab ?: return
        val mono = Typeface.MONOSPACE

        val root = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(8), px(6), px(8))
            background = bg(12)
        }
        val header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(svc).apply {
                text = "ШАБЛОНИ"
                setTextColor(DIM)
                textSize = 13f
                typeface = Typeface.create(mono, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            TextView(svc).apply {
                text = "✕"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                setPadding(px(12), px(4), px(8), px(4))
                setOnClickListener { closePanel() }
            },
        )
        root.addView(header)

        val list = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val templates = FlightBridge.templates?.invoke()
            ?: store.load<List<String>>("kap_templates", emptyList())
        if (templates.isEmpty()) {
            list.addView(
                TextView(svc).apply {
                    text = "Шаблонів немає —\nдодайте в застосунку"
                    setTextColor(DIM)
                    textSize = 13f
                    typeface = mono
                    setPadding(0, px(8), px(8), px(8))
                },
            )
        }
        for (t in templates) {
            list.addView(
                TextView(svc).apply {
                    text = t
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 15f
                    typeface = mono
                    setPadding(px(2), px(10), px(10), px(10))
                    setOnClickListener { pick(t) }
                },
            )
        }
        val scroll = ScrollView(svc).apply { addView(list) }
        list.measure(0, 0)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                minOf(list.measuredHeight, (dm.heightPixels * 0.7).toInt() - px(60)),
            ),
        )
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) closePanel()
            true
        }
        root.measure(0, 0)

        val w = root.measuredWidth.coerceIn(px(200), (dm.widthPixels * 0.5).toInt())
        val h = root.measuredHeight
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            width = w
            x = if (tabLp.x > dm.widthPixels / 2) tabLp.x - w - px(6) else tabLp.x + tab.width + px(6)
            y = (tabLp.y + tab.height / 2 - h / 2)
                .coerceIn(px(8), (dm.heightPixels - h - px(8)).coerceAtLeast(px(8)))
        }
        if (runCatching { wm.addView(root, lp) }.isFailure) return
        panel = root
        handler.postDelayed(autoHide, 15_000L)
    }

    private fun pick(text: String) {
        closePanel()
        FlightBridge.insertTemplate?.invoke(text) ?: FlightBridge.storeInsertTemplate(svc, text)
        FlightBridge.onStateChanged?.invoke()
        (svc.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE))
        Toast.makeText(svc, "✓ " + TimeFmt.hm(System.currentTimeMillis()) + " " + text, Toast.LENGTH_SHORT).show()
    }

    fun closePanel() {
        handler.removeCallbacks(autoHide)
        panel?.let {
            runCatching { wm.removeView(it) }
            panelClosedAt = System.currentTimeMillis()
        }
        panel = null
    }

    private companion object {
        const val BG = 0xE6121212.toInt()
        const val STROKE = 0xFF3A3A3A.toInt()
        const val DIM = 0xFFB0B0B0.toInt()
    }
}
