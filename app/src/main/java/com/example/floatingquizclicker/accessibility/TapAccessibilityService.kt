package com.example.floatingquizclicker.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class TapAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Tap accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun tap(
        x: Float,
        y: Float,
        durationMs: Long = DEFAULT_TAP_DURATION_MS,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onComplete?.invoke(false)
            return false
        }

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    onComplete?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    onComplete?.invoke(false)
                }
            },
            null
        )
    }

    companion object {
        private const val TAG = "TapAccessibilityService"
        private const val DEFAULT_TAP_DURATION_MS = 10L

        @Volatile
        private var instance: TapAccessibilityService? = null

        fun isReady(): Boolean = instance != null

        fun performTap(
            x: Float,
            y: Float,
            durationMs: Long = DEFAULT_TAP_DURATION_MS,
            onComplete: ((Boolean) -> Unit)? = null
        ): Boolean {
            val service = instance ?: run {
                onComplete?.invoke(false)
                return false
            }
            return service.tap(x, y, durationMs = durationMs, onComplete = onComplete)
        }
    }
}
