package com.example.floatingquizclicker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.floatingquizclicker.overlay.FloatingOverlayService

class MainActivity : android.app.Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(0xFFF7F8FA.toInt())
        }

        val title = TextView(this).apply {
            text = "Floating Quiz Clicker"
            textSize = 26f
            setTextColor(0xFF111827.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        val description = TextView(this).apply {
            text = "A small draggable overlay button performs one immediate tap at its current center, then one delayed tap at the button's live position after your configured delay."
            textSize = 15f
            setTextColor(0xFF374151.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(18))
        }
        root.addView(description, LinearLayout.LayoutParams(-1, -2))

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF111827.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(18)
        })

        root.addView(primaryButton("1. Grant Overlay Permission") {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                toast("Overlay permission is already granted.")
            }
        })

        root.addView(primaryButton("2. Enable Accessibility Tap Service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Open 'Floating Quiz Clicker Tap Service' and enable it.")
        })

        root.addView(primaryButton("3. Start Floating Overlay") {
            if (!Settings.canDrawOverlays(this)) {
                toast("Grant overlay permission first.")
                return@primaryButton
            }
            requestNotificationsIfNeeded()
            val intent = Intent(this, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            toast("Floating overlay started.")
        })

        root.addView(secondaryButton("Stop Floating Overlay") {
            stopService(Intent(this, FloatingOverlayService::class.java))
            toast("Floating overlay stopped.")
        })

        setContentView(root)
    }

    private fun primaryButton(label: String, onClick: View.OnClickListener): Button {
        return Button(this).apply {
            text = label
            textSize = 16f
            setOnClickListener(onClick)
        }.also { button ->
            button.layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) }
        }
    }

    private fun secondaryButton(label: String, onClick: View.OnClickListener): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            setOnClickListener(onClick)
        }.also { button ->
            button.layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) }
        }
    }

    private fun updateStatus() {
        val overlayStatus = if (Settings.canDrawOverlays(this)) "granted" else "not granted"
        val accessibilityStatus = if (isAccessibilityServiceEnabled()) "enabled" else "not enabled"
        statusText.text = "Overlay permission: $overlayStatus\nAccessibility tap service: $accessibilityStatus\n\nRequired on Android 14: both permissions must be enabled before taps can be injected over other apps."
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${com.example.floatingquizclicker.accessibility.TapAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (service in splitter) {
            if (service.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)

        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
