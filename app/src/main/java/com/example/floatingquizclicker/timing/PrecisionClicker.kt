package com.example.floatingquizclicker.timing

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class PrecisionClicker(private val onPerformTap: (String, (Boolean) -> Unit) -> Unit,
                       private val onSecondTapExecuted: ((Long) -> Unit)? = null) {

    private val TAG = "PrecisionClicker"
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val isRunning = AtomicBoolean(false)

    @Volatile
    var systemLatencyNanos: Long = 45_000_000L // Default 45ms

    fun start() {
        if (handlerThread == null) {
            handlerThread = HandlerThread("PrecisionClicker", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
                start()
                handler = Handler(looper)
            }
        }
        isRunning.set(true)
        Log.d(TAG, "PrecisionClicker started.")
    }

    fun stop() {
        isRunning.set(false)
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        Log.d(TAG, "PrecisionClicker stopped.")
    }

    fun scheduleTap(intervalMs: Float, isCalibration: Boolean = false) {
        if (!isRunning.get()) {
            Log.w(TAG, "PrecisionClicker is not running. Cannot schedule tap.")
            return
        }

        handler?.post {
            executePrecisionTap(intervalMs, isCalibration)
        }
    }

    private fun executePrecisionTap(intervalMs: Float, isCalibration: Boolean) {
        if (!isRunning.get()) return

        val intervalNanos = (intervalMs * 1_000_000).toLong()
        val effectiveIntervalNanos = if (isCalibration) intervalNanos else (intervalNanos - systemLatencyNanos).coerceAtLeast(0L)

        // First tap (immediate)
        onPerformTap("Immediate tap") { success ->
            if (!success) {
                Log.e(TAG, "First tap failed or was cancelled.")
                stop()
                return@onPerformTap
            }

            if (!isRunning.get()) return@onPerformTap

            val anchorTimeNanos = System.nanoTime()
            var nextTargetTimeNanos = anchorTimeNanos + effectiveIntervalNanos

            Log.d(TAG, "Scheduled next tap at: ${nextTargetTimeNanos / 1_000_000.0} ms from epoch")

            // Hybrid wait loop
            while (isRunning.get()) {
                val remainingNanos = nextTargetTimeNanos - System.nanoTime()

                if (remainingNanos <= 0) {
                    // Target time reached or passed
                    break
                }

                val remainingMs = remainingNanos / 1_000_000L

                if (remainingMs > 3) {
                    // Coarse wait
                    try {
                        Thread.sleep(1) // Sleep for 1ms
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        Log.w(TAG, "PrecisionClicker interrupted during sleep.")
                        isRunning.set(false)
                        return@onPerformTap
                    }
                } else {
                    // Fine wait (final 3ms)
                    while (System.nanoTime() < nextTargetTimeNanos) {
                        Thread.yield()
                    }
                    break
                }
            }

            if (!isRunning.get()) return@onPerformTap

            // Second tap (delayed)
            onPerformTap("Delayed tap") { success2 ->
                if (!success2) {
                    Log.e(TAG, "Second tap failed or was cancelled.")
                }
                // For calibration, we might want to schedule the next lap here
                // For normal operation, this is the end of the two-tap sequence
            val actualSecondTapTimeNanos = System.nanoTime()
            onSecondTapExecuted?.invoke(actualSecondTapTimeNanos)
            }
        }
    }
}
