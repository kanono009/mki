package com.example.floatingquizclicker.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.floatingquizclicker.accessibility.TapAccessibilityService
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: TextView
    private lateinit var panel: LinearLayout
    private lateinit var buttonParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var delayInput: EditText
    private lateinit var statusText: TextView
    private lateinit var antiDetectCheckbox: CheckBox

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var timingThread: HandlerThread
    private lateinit var timingHandler: Handler
    private val countdownToken = Any()
    private val random = Random()

    private var isPanelVisible = true

    @Volatile private var countdownRunning = false
    @Volatile private var isCalibrating = false

    // ── Precision timing (nanoTime-based) ────────────────────────────────────
    // Subtract from interval to centre click on target (tune via CALIBRATE)
    @Volatile private var systemLatencyNanos = 45_000_000L   // default 45 ms
    // Anchor for drift-free scheduling: next = prev + interval, never now + interval
    @Volatile private var nextTargetNanos = 0L

    private var buttonSize = 58 // Default size in dp
    private lateinit var scaleDetector: ScaleGestureDetector

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        timingThread = HandlerThread("PrecisionClicker", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        timingHandler = Handler(timingThread.looper)
        startForeground(NOTIFICATION_ID, buildNotification())
        createFloatingButton()
        createPanel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(countdownToken)
        if (::timingHandler.isInitialized) timingHandler.removeCallbacksAndMessages(countdownToken)
        if (::timingThread.isInitialized) timingThread.quitSafely()
        runCatching { windowManager.removeView(floatingButton) }
        runCatching { windowManager.removeView(panel) }
        super.onDestroy()
    }

    private fun createFloatingButton() {
        floatingButton = TextView(this).apply {
            text = "TAP"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(android.R.drawable.presence_online)
            elevation = dp(8).toFloat()
        }

        buttonParams = WindowManager.LayoutParams(
            dp(buttonSize),
            dp(buttonSize),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(120)
            y = dp(240)
        }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val newSize = (buttonSize * scaleFactor).roundToInt().coerceIn(32, 200)
                if (newSize != buttonSize) {
                    buttonSize = newSize
                    buttonParams.width = dp(buttonSize)
                    buttonParams.height = dp(buttonSize)
                    runCatching { windowManager.updateViewLayout(floatingButton, buttonParams) }
                }
                return true
            }
        })

        floatingButton.setOnTouchListener(ButtonDragTouchListener())
        windowManager.addView(floatingButton, buttonParams)
    }

    private fun createPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xEE111827.toInt())
            elevation = dp(10).toFloat()
        }

        val title = TextView(this).apply {
            text = "Floating Quiz Clicker"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
        }
        panel.addView(title, LinearLayout.LayoutParams(-1, -2))

        delayInput = EditText(this).apply {
            hint = "Delay seconds, e.g. 21.000"
            setText("21.000")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFCBD5E1.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    enablePanelInputMode()
                    view.requestFocus()
                    showKeyboard(view)
                }
                false
            }
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    enablePanelInputMode()
                    showKeyboard(view)
                }
            }
        }
        panel.addView(delayInput, LinearLayout.LayoutParams(-1, dp(52)))

        antiDetectCheckbox = CheckBox(this).apply {
            text = "Anti-Detect (Randomize Tap)"
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = true
        }
        panel.addView(antiDetectCheckbox, LinearLayout.LayoutParams(-1, -2))

        val startButton = Button(this).apply {
            text = "START COUNTDOWN"
            setOnClickListener { startCountdownFromPanel() }
        }
        panel.addView(startButton, LinearLayout.LayoutParams(-1, dp(48)))

        val stopButton = Button(this).apply {
            text = "CANCEL"
            setOnClickListener { cancelCountdown("Countdown cancelled.") }
        }
        panel.addView(stopButton, LinearLayout.LayoutParams(-1, dp(44)))

        val calibrateButton = Button(this).apply {
            text = "CALIBRATE (10 laps)"
            setOnClickListener { startCalibration() }
        }
        panel.addView(calibrateButton, LinearLayout.LayoutParams(-1, dp(44)))

        statusText = TextView(this).apply {
            text = "Drag TAP over target. Set delay. Press START COUNTDOWN. Pinch TAP to resize."
            textSize = 12f
            setTextColor(0xFFE5E7EB.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        panel.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        panelParams = WindowManager.LayoutParams(
            dp(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            panelPassiveFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(70)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        panel.setOnTouchListener(PanelDragTouchListener())
        windowManager.addView(panel, panelParams)
    }

    private fun startCountdownFromPanel() {
        hideKeyboardAndRestorePassivePanel()

        if (!TapAccessibilityService.isReady()) {
            toast("Enable the accessibility tap service first.")
            statusText.text = "Accessibility service is not enabled."
            return
        }

        val delayMs = parseDelayMillis(delayInput.text?.toString().orEmpty())
        if (delayMs == null) {
            toast("Delay must be 0.000 to 31.000 seconds.")
            return
        }

        mainHandler.removeCallbacksAndMessages(countdownToken)
        timingHandler.removeCallbacksAndMessages(countdownToken)
        countdownRunning = true

        // Anchor: set first target using nanoTime, then every subsequent tap uses += interval
        val intervalNanos = delayMs * 1_000_000L
        nextTargetNanos = System.nanoTime() + intervalNanos
        statusText.text = "Running: immediate tap now; second tap scheduled at +${formatDelay(delayMs)} s. Button stays draggable."

        performTapAtCurrentButtonCenter("Immediate tap")
        scheduleDelayedTap(intervalNanos)
    }

    private fun scheduleDelayedTap(intervalNanos: Long) {
        val runnable = Runnable {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)

            val fireAt = nextTargetNanos - systemLatencyNanos

            // Pure spin — no sleep, no yield, nothing else
            while (countdownRunning && System.nanoTime() < fireAt) { }

            if (!countdownRunning) return@Runnable

            performTapAtCurrentButtonCenter("Delayed tap")
            countdownRunning = false
            mainHandler.post { statusText.text = "Tap fired." }
        }

        timingHandler.post(runnable)
    }

    /**
     * 10-lap calibration with systemLatencyNanos = 0.
     * Measures raw offset between scheduled and actual nanoTime fire.
     * Sets systemLatencyNanos to the average so real taps land centred on target.
     */
    private fun startCalibration() {
        if (isCalibrating) { toast("Calibration already running."); return }
        isCalibrating = true
        val savedLatency = systemLatencyNanos
        systemLatencyNanos = 0L   // raw measurement mode
        statusText.text = "Calibrating... 10 laps (latency=0)"

        timingHandler.post {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val intervalNanos = 2_000_000_000L   // 2-second laps
            val offsets = mutableListOf<Long>()

            var lapTarget = System.nanoTime() + intervalNanos

            repeat(10) { i ->
                // Hybrid wait
                var rem = lapTarget - System.nanoTime()
                while (rem > 3_000_000L) { Thread.sleep(1L); rem = lapTarget - System.nanoTime() }
                while (System.nanoTime() < lapTarget) { Thread.yield() }

                val actual = System.nanoTime()
                val offset = actual - lapTarget   // positive = fired late
                offsets.add(offset)

                val offsetMs = "%.2f".format(offset / 1_000_000.0)
                mainHandler.post { statusText.text = "Lap ${i + 1}/10  offset=${offsetMs}ms" }

                // Drift-free: next lap = prev lap + interval
                lapTarget += intervalNanos
                Thread.sleep(100L)   // brief pause between laps
            }

            val avgNanos = offsets.map { it }.average().toLong()
            systemLatencyNanos = avgNanos.coerceAtLeast(0L)
            isCalibrating = false

            val avgMs = "%.2f".format(avgNanos / 1_000_000.0)
            mainHandler.post {
                statusText.text = "Calibration done. avg offset=${avgMs}ms → systemLatency=${systemLatencyNanos / 1_000_000L}ms"
                toast("Calibrated! Latency set to ${systemLatencyNanos / 1_000_000L}ms")
            }
        }
    }

    private fun performTapAtCurrentButtonCenter(label: String) {
        var centerX = buttonParams.x + buttonParams.width / 2f
        var centerY = buttonParams.y + buttonParams.height / 2f
        var duration = TAP_DURATION_MS

        if (antiDetectCheckbox.isChecked) {
            // Randomize coordinates within 15% of button size
            val range = (buttonParams.width * 0.15f).toInt().coerceAtLeast(1)
            centerX += random.nextInt(range * 2 + 1) - range
            centerY += random.nextInt(range * 2 + 1) - range
            // Randomize duration between 8ms and 25ms
            duration = (8 + random.nextInt(18)).toLong()
        }

        setButtonPassThrough(true)
        val dispatched = TapAccessibilityService.performTap(centerX, centerY, durationMs = duration) { success ->
            mainHandler.postDelayed({ setButtonPassThrough(false) }, BUTTON_RESTORE_DELAY_MS)
            if (!success) {
                mainHandler.post { statusText.text = "$label failed or was cancelled by the system." }
            }
        }
        if (!dispatched) {
            setButtonPassThrough(false)
            mainHandler.post { statusText.text = "$label could not be dispatched. Check Accessibility permission." }
        }
    }

    private fun setButtonPassThrough(passThrough: Boolean) {
        val update = Runnable {
            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            buttonParams.flags = if (passThrough) {
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                baseFlags
            }
            runCatching { windowManager.updateViewLayout(floatingButton, buttonParams) }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            update.run()
        } else {
            val latch = CountDownLatch(1)
            mainHandler.post {
                update.run()
                latch.countDown()
            }
            runCatching { latch.await(20L, TimeUnit.MILLISECONDS) }
        }
    }

    private fun cancelCountdown(message: String) {
        mainHandler.removeCallbacksAndMessages(countdownToken)
        timingHandler.removeCallbacksAndMessages(countdownToken)
        countdownRunning = false
        statusText.text = message
    }

    private fun togglePanel() {
        isPanelVisible = !isPanelVisible
        panel.visibility = if (isPanelVisible) View.VISIBLE else View.GONE
    }

    private fun parseDelayMillis(raw: String): Long? {
        val trimmed = raw.trim()
        if (!trimmed.matches(Regex("^\\d{1,2}(\\.\\d{0,3})?$"))) return null
        val seconds = runCatching { BigDecimal(trimmed) }.getOrNull() ?: return null
        if (seconds < BigDecimal.ZERO || seconds > BigDecimal("31.000")) return null
        return seconds.multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    private fun formatDelay(delayMs: Long): String = "%.3f".format(delayMs / 1000.0)

    private fun panelPassiveFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun panelInputFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun enablePanelInputMode() {
        panelParams.flags = panelInputFlags()
        panelParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        runCatching { windowManager.updateViewLayout(panel, panelParams) }
    }

    private fun hideKeyboardAndRestorePassivePanel() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(delayInput.windowToken, 0)
        delayInput.clearFocus()
        panelParams.flags = panelPassiveFlags()
        runCatching { windowManager.updateViewLayout(panel, panelParams) }
    }

    private fun showKeyboard(view: View) {
        view.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }, 80L)
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Quiz Clicker",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Floating Quiz Clicker is running")
            .setContentText("Drag the TAP button and use the panel to start the two-tap countdown.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private inner class ButtonDragTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) return true

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = buttonParams.x
                    startY = buttonParams.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > dp(3) || abs(dy) > dp(3)) moved = true
                    buttonParams.x = startX + dx.roundToInt()
                    buttonParams.y = startY + dy.roundToInt()
                    windowManager.updateViewLayout(floatingButton, buttonParams)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    return true
                }
            }
            return true
        }
    }

    private inner class PanelDragTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (delayInput.hasFocus()) return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = panelParams.x
                    startY = panelParams.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && abs(dx) < dp(8) && abs(dy) < dp(8)) return true
                    dragging = true
                    panelParams.x = startX + dx.roundToInt()
                    panelParams.y = startY + dy.roundToInt()
                    windowManager.updateViewLayout(panel, panelParams)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
            }
            return false
        }
    }

    companion object {
        private const val CHANNEL_ID = "floating_quiz_clicker_overlay"
        private const val NOTIFICATION_ID = 2401
        private const val TAP_DURATION_MS = 10L
        private const val BUTTON_RESTORE_DELAY_MS = 20L
    }
}
