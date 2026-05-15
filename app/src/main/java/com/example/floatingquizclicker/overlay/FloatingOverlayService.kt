package com.example.floatingquizclicker.overlay

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.floatingquizclicker.accessibility.TapAccessibilityService
import java.math.BigDecimal
import java.math.RoundingMode
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

    private val mainHandler = Handler(Looper.getMainLooper())

    // Dedicated high-priority thread: runs the coarse sleep + nanosecond busy-wait
    private lateinit var timingThread: HandlerThread
    private lateinit var timingHandler: Handler

    // AlarmManager keeps CPU awake through Doze for long delays
    private lateinit var alarmManager: AlarmManager
    private var wakeReceiver: BroadcastReceiver? = null

    private val countdownToken = Any()
    private var isPanelVisible = true

    @Volatile private var countdownRunning = false

    // Target expressed in elapsedRealtimeNanos — monotonic, never paused by Doze
    @Volatile private var targetElapsedNs = 0L
    @Volatile private var requestedDelayMs = 0L

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        alarmManager  = getSystemService(ALARM_SERVICE)  as AlarmManager

        timingThread = HandlerThread("FQC-Timing", Process.THREAD_PRIORITY_URGENT_DISPLAY)
            .also { it.start() }
        timingHandler = Handler(timingThread.looper)

        startForeground(NOTIFICATION_ID, buildNotification())
        createFloatingButton()
        createPanel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelCountdown("Service destroyed.")
        if (::timingThread.isInitialized) timingThread.quitSafely()
        runCatching { windowManager.removeView(floatingButton) }
        runCatching { windowManager.removeView(panel) }
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // UI – floating button
    // -------------------------------------------------------------------------

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
            dp(58), dp(58), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(120); y = dp(240)
        }
        floatingButton.setOnTouchListener(ButtonDragTouchListener())
        windowManager.addView(floatingButton, buttonParams)
    }

    // -------------------------------------------------------------------------
    // UI – control panel
    // -------------------------------------------------------------------------

    private fun createPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xEE111827.toInt())
            elevation = dp(10).toFloat()
        }

        panel.addView(TextView(this).apply {
            text = "Floating Quiz Clicker"; textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(-1, -2))

        delayInput = EditText(this).apply {
            hint = "Delay seconds, e.g. 21.000"
            setText("21.000")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true); textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFCBD5E1.toInt())
            isFocusable = true; isFocusableInTouchMode = true
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    enablePanelInputMode()
                    view.requestFocus()
                    showKeyboard(view)
                }
                false
            }
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) { enablePanelInputMode(); showKeyboard(view) }
            }
        }
        panel.addView(delayInput, LinearLayout.LayoutParams(-1, dp(52)))

        panel.addView(Button(this).apply {
            text = "START COUNTDOWN"
            setOnClickListener { startCountdownFromPanel() }
        }, LinearLayout.LayoutParams(-1, dp(48)))

        panel.addView(Button(this).apply {
            text = "CANCEL"
            setOnClickListener { cancelCountdown("Countdown cancelled.") }
        }, LinearLayout.LayoutParams(-1, dp(44)))

        statusText = TextView(this).apply {
            text = "Drag TAP over target. Set delay. Press START COUNTDOWN."
            textSize = 12f; setTextColor(0xFFE5E7EB.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        panel.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        panelParams = WindowManager.LayoutParams(
            dp(280), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), panelPassiveFlags(), PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18); y = dp(70)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        panel.setOnTouchListener(PanelDragTouchListener())
        windowManager.addView(panel, panelParams)
    }

    // -------------------------------------------------------------------------
    // Countdown – entry point
    // -------------------------------------------------------------------------

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

        // Cancel anything running
        cancelCountdown("")
        countdownRunning = true
        requestedDelayMs = delayMs
        statusText.text = "Running: firing immediate tap…"

        // ── ANCHOR STRATEGY ──────────────────────────────────────────────────
        //
        // Problem with the old code:
        //   anchor = SystemClock.uptimeMillis()  ← millisecond precision only
        //   spin   = Thread.yield()              ← yields CPU; OS may not
        //                                          reschedule for 10-20 ms
        //   clock  = uptimeMillis               ← pauses during deep sleep
        //
        // New approach:
        //   1. Capture anchorNs = elapsedRealtimeNanos() BEFORE dispatchGesture.
        //      elapsedRealtimeNanos() is monotonic and keeps ticking in Doze.
        //   2. Add DISPATCH_LATENCY_NS to approximate when the DOWN event
        //      actually touches the screen (dispatchGesture has ~6 ms internal
        //      scheduling delay before the kernel injects the DOWN event).
        //   3. targetElapsedNs = anchorNs + delayMs converted to ns.
        //   4. AlarmManager.setExactAndAllowWhileIdle wakes CPU 250 ms before
        //      the target, defeating Doze.
        //   5. On the URGENT_DISPLAY thread: coarse Thread.sleep down to
        //      SPIN_LEAD_NS from target, then a pure nanosecond busy-wait.
        //      No Thread.yield() — that surrenders the CPU to the scheduler.
        //
        // ─────────────────────────────────────────────────────────────────────

        val anchorNs = SystemClock.elapsedRealtimeNanos() + DISPATCH_LATENCY_NS
        targetElapsedNs = anchorNs + delayMs * NS_PER_MS

        // Fire tap 1 immediately (fire-and-forget; no callback needed for timing)
        dispatchTap("Immediate tap", onDone = null)

        // Start the live display and the precision timer for tap 2
        startLiveCountdown()
        scheduleSecondTap()
    }

    // -------------------------------------------------------------------------
    // Countdown – live display
    // -------------------------------------------------------------------------

    private fun startLiveCountdown() {
        mainHandler.removeCallbacksAndMessages(countdownToken)
        val tick = object : Runnable {
            override fun run() {
                if (!countdownRunning) return
                val remainNs = (targetElapsedNs - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(0L)
                val remainMs = (remainNs + NS_PER_MS - 1L) / NS_PER_MS
                statusText.text = "Running: ${formatDelay(remainMs)} s remaining; " +
                        "target +${formatDelay(requestedDelayMs)} s. Button stays draggable."
                if (remainNs > 0L)
                    mainHandler.postAtTime(this, countdownToken, SystemClock.uptimeMillis() + 16L)
            }
        }
        mainHandler.post(tick)
    }

    // -------------------------------------------------------------------------
    // Countdown – precision scheduler for tap 2
    //
    //  Stage 1: AlarmManager fires ALARM_LEAD_MS before target → wakes CPU
    //  Stage 2: Coarse Thread.sleep loop on URGENT_DISPLAY thread
    //           → sleeps in chunks, stopping SPIN_LEAD_NS before target
    //  Stage 3: Nanosecond busy-wait (no yield, no sleep)
    //           → fires tap at the exact nanosecond
    // -------------------------------------------------------------------------

    private fun scheduleSecondTap() {
        val targetNs = targetElapsedNs          // local copy; avoids volatile reads in hot loop

        // ── Stage 3 + 2 runnable (posted to high-priority timing thread) ────
        val precisionRunnable = Runnable {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)

            // Stage 2 – coarse sleep, shrinking chunks, stop SPIN_LEAD_NS early
            while (countdownRunning) {
                val remainNs = targetNs - SystemClock.elapsedRealtimeNanos()
                if (remainNs <= SPIN_LEAD_NS) break
                val sleepNs = remainNs - SPIN_LEAD_NS
                if (sleepNs >= NS_PER_MS) {
                    // Sleep (sleepNs − 0.5 ms) to avoid overshooting
                    Thread.sleep((sleepNs - 500_000L) / NS_PER_MS)
                } else {
                    // Sub-millisecond: use nanosecond sleep overload
                    Thread.sleep(0L, sleepNs.toInt().coerceIn(100_000, 999_999))
                }
            }

            // Stage 3 – nanosecond busy-wait; deliberately NO Thread.yield()
            while (countdownRunning) {
                if (SystemClock.elapsedRealtimeNanos() >= targetNs) break
            }

            if (!countdownRunning) return@Runnable

            dispatchTap("Delayed tap") { success ->
                countdownRunning = false
                mainHandler.removeCallbacksAndMessages(countdownToken)
                mainHandler.post {
                    statusText.text = if (success)
                        "Completed delayed tap at live button position."
                    else
                        "Delayed tap failed or was cancelled by the system."
                }
            }
        }

        // ── Stage 1 – AlarmManager wakeup ────────────────────────────────────
        val targetElapsedMs = targetNs / NS_PER_MS
        val alarmElapsedMs  = targetElapsedMs - ALARM_LEAD_MS
        val nowElapsedMs    = SystemClock.elapsedRealtime()

        if (alarmElapsedMs - nowElapsedMs > 50L) {
            // Enough headroom: arm AlarmManager, post timingHandler from receiver
            val action = ACTION_PRECISION_ALARM
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val recv = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, i: Intent?) {
                    cancelWakeReceiver()
                    if (countdownRunning) timingHandler.post(precisionRunnable)
                }
            }
            wakeReceiver = recv
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(recv, IntentFilter(action), RECEIVER_NOT_EXPORTED)
            else
                registerReceiver(recv, IntentFilter(action))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmElapsedMs, pi)
            else
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmElapsedMs, pi)
        } else {
            // Target is imminent; skip alarm and post precision runnable now
            timingHandler.post(precisionRunnable)
        }
    }

    // -------------------------------------------------------------------------
    // Tap dispatch helper
    // -------------------------------------------------------------------------

    private fun dispatchTap(label: String, onDone: ((Boolean) -> Unit)?) {
        val cx = buttonParams.x + floatingButton.width.coerceAtLeast(dp(58)) / 2f
        val cy = buttonParams.y + floatingButton.height.coerceAtLeast(dp(58)) / 2f

        setButtonPassThrough(true)
        val ok = TapAccessibilityService.performTap(cx, cy, durationMs = TAP_DURATION_MS) { success ->
            mainHandler.postDelayed({ setButtonPassThrough(false) }, BUTTON_RESTORE_MS)
            if (!success) mainHandler.post { statusText.text = "$label failed." }
            onDone?.invoke(success)
        }
        if (!ok) {
            setButtonPassThrough(false)
            mainHandler.post {
                statusText.text = "$label could not be dispatched. Check Accessibility permission."
            }
            onDone?.invoke(false)
        }
    }

    private fun setButtonPassThrough(passThrough: Boolean) {
        val update = Runnable {
            val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            buttonParams.flags = if (passThrough) base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                 else base
            runCatching { windowManager.updateViewLayout(floatingButton, buttonParams) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update.run()
        } else {
            val latch = CountDownLatch(1)
            mainHandler.post { update.run(); latch.countDown() }
            runCatching { latch.await(20L, TimeUnit.MILLISECONDS) }
        }
    }

    // -------------------------------------------------------------------------
    // Cancel / cleanup
    // -------------------------------------------------------------------------

    private fun cancelCountdown(message: String) {
        mainHandler.removeCallbacksAndMessages(countdownToken)
        timingHandler.removeCallbacksAndMessages(countdownToken)
        cancelWakeReceiver()
        countdownRunning = false
        if (message.isNotEmpty()) statusText.text = message
    }

    private fun cancelWakeReceiver() {
        wakeReceiver?.let { runCatching { unregisterReceiver(it) }; wakeReceiver = null }
    }

    // -------------------------------------------------------------------------
    // Panel / keyboard helpers
    // -------------------------------------------------------------------------

    private fun togglePanel() {
        isPanelVisible = !isPanelVisible
        panel.visibility = if (isPanelVisible) View.VISIBLE else View.GONE
    }

    private fun parseDelayMillis(raw: String): Long? {
        val trimmed = raw.trim()
        if (!trimmed.matches(Regex("^\\d{1,2}(\\.\\d{0,3})?\$"))) return null
        val s = runCatching { BigDecimal(trimmed) }.getOrNull() ?: return null
        if (s < BigDecimal.ZERO || s > BigDecimal("31.000")) return null
        return s.multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    private fun formatDelay(ms: Long) = "%.3f".format(ms / 1000.0)

    private fun panelPassiveFlags() =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun panelInputFlags() =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun enablePanelInputMode() {
        panelParams.flags = panelInputFlags()
        panelParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        runCatching { windowManager.updateViewLayout(panel, panelParams) }
    }

    private fun hideKeyboardAndRestorePassivePanel() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(delayInput.windowToken, 0)
        delayInput.clearFocus()
        panelParams.flags = panelPassiveFlags()
        runCatching { windowManager.updateViewLayout(panel, panelParams) }
    }

    private fun showKeyboard(view: View) {
        view.postDelayed({
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }, 80L)
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Floating Quiz Clicker",
                    NotificationManager.IMPORTANCE_LOW))

        return (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("Floating Quiz Clicker is running")
            .setContentText("Drag the TAP button and use the panel to start the two-tap countdown.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // -------------------------------------------------------------------------
    // Touch listeners (unchanged behaviour)
    // -------------------------------------------------------------------------

    private inner class ButtonDragTouchListener : View.OnTouchListener {
        private var downRawX = 0f; private var downRawY = 0f
        private var startX = 0; private var startY = 0; private var moved = false
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = buttonParams.x; startY = buttonParams.y; moved = false; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX; val dy = event.rawY - downRawY
                    if (abs(dx) > dp(3) || abs(dy) > dp(3)) moved = true
                    buttonParams.x = startX + dx.roundToInt(); buttonParams.y = startY + dy.roundToInt()
                    windowManager.updateViewLayout(floatingButton, buttonParams); return true }
                MotionEvent.ACTION_UP -> { if (!moved) togglePanel(); return true }
            }
            return true
        }
    }

    private inner class PanelDragTouchListener : View.OnTouchListener {
        private var downRawX = 0f; private var downRawY = 0f
        private var startX = 0; private var startY = 0; private var dragging = false
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (delayInput.hasFocus()) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startX = panelParams.x; startY = panelParams.y; dragging = false; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX; val dy = event.rawY - downRawY
                    if (!dragging && abs(dx) < dp(8) && abs(dy) < dp(8)) return true
                    dragging = true
                    panelParams.x = startX + dx.roundToInt(); panelParams.y = startY + dy.roundToInt()
                    windowManager.updateViewLayout(panel, panelParams); return true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; return true }
            }
            return false
        }
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        private const val CHANNEL_ID        = "floating_quiz_clicker_overlay"
        private const val NOTIFICATION_ID   = 2401
        private const val TAP_DURATION_MS   = 10L
        private const val BUTTON_RESTORE_MS = 20L
        private const val NS_PER_MS         = 1_000_000L

        // Action broadcast for AlarmManager wakeup
        private const val ACTION_PRECISION_ALARM =
            "com.example.floatingquizclicker.PRECISION_ALARM"

        /**
         * Estimated time between calling dispatchGesture() and the DOWN event
         * actually being injected by the kernel (~4–8 ms on most devices).
         * This offsets the deadline so "21.000 s" means 21.000 s after the
         * in-app DOWN event, not 21.000 s after our call to dispatchGesture().
         *
         * HOW TO TUNE:
         *   Measured consistently EARLY by X ms → decrease by X * NS_PER_MS
         *   Measured consistently LATE  by X ms → increase by X * NS_PER_MS
         */
        private const val DISPATCH_LATENCY_NS = 6L * NS_PER_MS   // 6 ms

        /**
         * How far before the target the nanosecond busy-wait starts.
         * 3 ms is enough runway without wasting too much CPU.
         */
        private const val SPIN_LEAD_NS = 3L * NS_PER_MS           // 3 ms

        /**
         * AlarmManager fires this many ms before the spin window so the CPU
         * is fully awake before we start sleeping in sub-millisecond chunks.
         */
        private const val ALARM_LEAD_MS = 250L
    }
}
