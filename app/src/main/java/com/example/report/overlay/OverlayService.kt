package com.example.report.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.report.MainActivity
import com.example.report.R
import com.example.report.core.Loss
import kotlin.math.abs

/**
 * Foreground service drawing a draggable takeoff/landing button over other apps
 * (TYPE_APPLICATION_OVERLAY). Tap → confirm popup ("Взліт?" / "Посадка?"); while flying the
 * popup also offers "ВТРАТА БОРТУ" which opens the app's loss screen with the chosen cause.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var view: ImageView? = null
    private var confirm: LinearLayout? = null
    private var confirmClosedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { dismissConfirm() }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Плаваюча кнопка", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Кнопка «Взліт/Сів» активна")
            .setContentText("Плаваюча кнопка поверх застосунків")
            .setOngoing(true)
            .build()
        startForeground(42, notification)

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_takeoff_fab)
            setBackgroundColor(0)
            val p = (8f * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 300
        }
        iv.setOnTouchListener(DragListener(iv))
        view = iv
        runCatching { wm.addView(iv, params) }
        refreshIcon()
        FlightBridge.onStateChanged = { view?.post { refreshIcon() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshIcon()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        FlightBridge.onStateChanged = null
        dismissConfirm()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun refreshIcon() {
        val flying = FlightBridge.flying(this)
        view?.setImageResource(if (flying) R.drawable.ic_landing_fab else R.drawable.ic_takeoff_fab)
    }

    private fun px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private inner class DragListener(private val v: View) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var pressed = false

        override fun onTouch(view: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false; pressed = true
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!pressed) return true
                    pressed = false
                    if (dragging || SystemClock.uptimeMillis() - confirmClosedAt <= 400) return true
                    showFlightPopup()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!pressed) return true
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!dragging && (abs(dx) > 12 || abs(dy) > 12)) {
                        dragging = true
                        dismissConfirm()
                    }
                    if (!dragging) return true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { wm.updateViewLayout(v, params) }
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

    private fun showFlightPopup() {
        val flying = FlightBridge.flying(this)
        showPopup(if (flying) "Посадка?" else "Взліт?", 5000L) { box ->
            val no = popupBtn("НІ", DIM) { dismissConfirm() }
            val yes = popupBtn(if (flying) "СІВ" else "ВЗЛІТ", if (flying) RED else GREEN) { confirmToggle() }
            if (flying) {
                box.addView(row(no, yes, popupBtn("ВТРАТА БОРТУ", ORANGE) { showLossPopup() }))
                box.addView(
                    popupBtn("НЕ ЗБЕРІГАТИ ВИЛІТ", DIM) { showCancelPopup() },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = px(10) },
                )
            } else {
                box.addView(row(no, yes))
            }
        }
    }

    private fun confirmToggle() {
        dismissConfirm()
        FlightBridge.toggle?.invoke() ?: FlightBridge.storeToggle(this)
        FlightBridge.onStateChanged?.invoke()
        refreshIcon()
        vibrate()
    }

    private fun showLossPopup() {
        showPopup("Причина втрати?", 10_000L) { box ->
            for (cause in Loss.CAUSES) {
                box.addView(
                    popupBtn(Loss.label(cause), ORANGE) { reportLoss(cause) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = px(10) },
                )
            }
            box.addView(
                popupBtn("СВОЯ ПРИЧИНА…", ORANGE) { reportLoss(Loss.CUSTOM) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = px(10) },
            )
            box.addView(
                popupBtn("НАЗАД", DIM) { showFlightPopup() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = px(10) },
            )
        }
    }

    /** «Не зберігати виліт»: confirm, then drop the active flight (mis-pressed takeoff). */
    private fun showCancelPopup() {
        showPopup("Видалити виліт? У звіти не піде", 8000L) { box ->
            box.addView(row(
                popupBtn("НАЗАД", DIM) { showFlightPopup() },
                popupBtn("ВИДАЛИТИ", RED) {
                    dismissConfirm()
                    FlightBridge.cancelFlight?.invoke() ?: FlightBridge.storeCancel(this)
                    FlightBridge.onStateChanged?.invoke()
                    refreshIcon()
                    vibrate()
                },
            ))
        }
    }

    private fun reportLoss(cause: String) {
        dismissConfirm()
        vibrate()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("loss_cause", cause)
                .putExtra("loss_tok", FlightBridge.lossToken)
                .putExtra("loss_at", System.currentTimeMillis()),
        )
    }

    private fun vibrate() {
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun popupBtn(label: String, color: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(color)
        textSize = 16f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        gravity = Gravity.CENTER
        minWidth = px(64)
        setPadding(px(16), px(14), px(16), px(14))
        background = GradientDrawable().apply {
            cornerRadius = px(8).toFloat()
            setStroke(px(1), STROKE)
        }
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, px(8), 0, px(4))
        views.forEachIndexed { i, v ->
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.marginStart = px(28)
            addView(v, lp)
        }
    }

    /** Shows a popup card next to the button (right, else left, else below/above). */
    @SuppressLint("ClickableViewAccessibility")
    private fun showPopup(title: String, timeoutMs: Long, content: (LinearLayout) -> Unit) {
        dismissConfirm()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(10), px(14), px(12))
            background = GradientDrawable().apply {
                setColor(POPUP_BG)
                cornerRadius = px(10).toFloat()
                setStroke(px(1), STROKE)
            }
        }
        box.addView(
            TextView(this).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            },
        )
        content(box)
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) dismissConfirm()
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        box.measure(0, 0)

        val btnW = view?.width?.takeIf { it > 0 } ?: px(72)
        val btnH = view?.height?.takeIf { it > 0 } ?: px(72)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val w = box.measuredWidth
        val h = box.measuredHeight
        when {
            params.x + btnW + px(6) + w <= screenW -> {
                lp.x = params.x + btnW + px(6)
                lp.y = params.y
            }
            params.x - w - px(6) >= 0 -> {
                lp.x = params.x - w - px(6)
                lp.y = params.y
            }
            else -> {
                lp.x = params.x.coerceIn(0, (screenW - w).coerceAtLeast(0))
                lp.y = if (params.y + btnH + px(6) + h <= screenH) {
                    params.y + btnH + px(6)
                } else {
                    (params.y - h - px(6)).coerceAtLeast(0)
                }
            }
        }
        if (runCatching { wm.addView(box, lp) }.isFailure) return
        confirm = box
        handler.postDelayed(autoHide, timeoutMs)
    }

    private fun dismissConfirm() {
        handler.removeCallbacks(autoHide)
        confirm?.let {
            runCatching {
                wm.removeView(it)
                confirmClosedAt = SystemClock.uptimeMillis()
            }
        }
        confirm = null
    }

    companion object {
        private const val CHANNEL_ID = "overlay_button"
        private const val POPUP_BG = 0xF0121212.toInt()
        private const val STROKE = 0xFF3A3A3A.toInt()
        private const val DIM = 0xFFB0B0B0.toInt()
        private const val RED = 0xFFE57373.toInt()
        private const val GREEN = 0xFF4CAF50.toInt()
        private const val ORANGE = 0xFFFF8A65.toInt()

        // TODO(decomp): inlined at call sites; reconstructed as a Companion helper.
        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }
    }
}
