package com.example.floatingquizclicker.input

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * RootTapInjector
 *
 * Replaces AccessibilityService.dispatchGesture() with direct kernel input injection.
 *
 * Pipeline:
 *   Kotlin → persistent su shell → tap_inject binary → /dev/input/eventX → kernel
 *
 * This bypasses AccessibilityManagerService, InputDispatcher, and SurfaceFlinger
 * entirely, reducing tap latency from ~40-60ms to ~2-4ms and eliminating variance.
 */
class RootTapInjector(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tap_injector", Context.MODE_PRIVATE)

    // Persistent root shell — opened once, reused for every tap
    private var suProcess: Process? = null
    private var suStream: DataOutputStream? = null

    // Path to extracted binary on internal storage
    private val binaryPath: String =
        "${context.filesDir.absolutePath}/tap_inject"

    // Cached touch device node e.g. /dev/input/event3
    @Volatile
    private var eventNode: String? = prefs.getString(PREF_EVENT_NODE, null)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Call once at service start.
     * Extracts binary, detects touch node, opens persistent root shell.
     */
    fun init(): Boolean {
        if (!extractBinary()) {
            Log.e(TAG, "Binary extraction failed")
            return false
        }
        if (eventNode == null) {
            eventNode = detectTouchNode()
            if (eventNode != null) {
                prefs.edit().putString(PREF_EVENT_NODE, eventNode).apply()
                Log.i(TAG, "Touch node detected and cached: $eventNode")
            } else {
                Log.e(TAG, "Could not detect touch event node")
                return false
            }
        }
        return openRootShell()
    }

    /** Call in onDestroy to clean up the su process. */
    fun destroy() {
        runCatching { suStream?.close() }
        runCatching { suProcess?.destroy() }
        suStream = null
        suProcess = null
    }

    // ── Tap dispatch ──────────────────────────────────────────────────────────

    /**
     * Inject a tap at (x, y) via the native binary.
     * Thread-safe. Called from the precision timer thread.
     * Returns immediately — the native binary handles the 50ms stroke duration.
     */
    @Synchronized
    fun tap(x: Float, y: Float): Boolean {
        val node = eventNode ?: return false
        val stream = suStream ?: run {
            // Shell died — reopen
            if (!openRootShell()) return false
            suStream!!
        }
        return try {
            stream.writeBytes("$binaryPath $node ${x.toInt()} ${y.toInt()}\n")
            stream.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "tap write failed: ${e.message}")
            // Shell died — close and reopen on next call
            runCatching { suStream?.close() }
            runCatching { suProcess?.destroy() }
            suStream = null
            suProcess = null
            false
        }
    }

    // ── Touch node detection ──────────────────────────────────────────────────

    /**
     * Detect the correct /dev/input/eventX for the touchscreen.
     *
     * Strategy 1: Run `getevent -lp` via su and parse for ABS_MT_POSITION_X
     *             + ABS_MT_POSITION_Y — the node reporting both is the touchscreen.
     *
     * Strategy 2 (fallback): Read /sys/class/input/eventN/name and match
     *             known touchscreen name substrings.
     */
    private fun detectTouchNode(): String? {
        // Strategy 1: getevent
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "getevent -lp"))
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()

            val lines = output.lines()
            var currentNode: String? = null
            var hasX = false
            var hasY = false

            for (line in lines) {
                // New device block e.g. "add device 3: /dev/input/event3"
                if (line.startsWith("add device")) {
                    // Check previous device
                    if (hasX && hasY && currentNode != null) return currentNode
                    // Reset for new device
                    val colonIdx = line.lastIndexOf(':')
                    currentNode = if (colonIdx >= 0) line.substring(colonIdx + 1).trim() else null
                    hasX = false
                    hasY = false
                }
                if (line.contains("ABS_MT_POSITION_X")) hasX = true
                if (line.contains("ABS_MT_POSITION_Y")) hasY = true
            }
            // Check last device
            if (hasX && hasY && currentNode != null) return currentNode
        } catch (e: Exception) {
            Log.w(TAG, "getevent detection failed: ${e.message}")
        }

        // Strategy 2: /sys/class/input name matching
        val touchNames = listOf("touchscreen", "touch", "fts", "synaptics",
            "goodix", "atmel", "novatek", "ft5x", "gt9")
        try {
            val sysInput = File("/sys/class/input")
            sysInput.listFiles()?.forEach { entry ->
                val nameFile = File(entry, "name")
                if (nameFile.exists()) {
                    val name = nameFile.readText().trim().lowercase()
                    if (touchNames.any { name.contains(it) }) {
                        val eventName = entry.name  // e.g. "event3"
                        val node = "/dev/input/$eventName"
                        Log.i(TAG, "Fallback match: $node ($name)")
                        return node
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sysfs detection failed: ${e.message}")
        }

        return null
    }

    // ── Binary extraction ─────────────────────────────────────────────────────

    /**
     * Extract the correct ABI binary from assets to internal storage.
     * Skips if already extracted and executable.
     */
    private fun extractBinary(): Boolean {
        val outFile = File(binaryPath)
        if (outFile.exists() && outFile.canExecute()) return true

        val abiName = selectAbi() ?: run {
            Log.e(TAG, "No compatible ABI binary found in assets")
            return false
        }

        return try {
            context.assets.open(abiName).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            outFile.setExecutable(true, false)
            // Also chmod via root to ensure kernel accepts it
            Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 755 $binaryPath")).waitFor()
            Log.i(TAG, "Binary extracted: $binaryPath (from $abiName)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}")
            false
        }
    }

    private fun selectAbi(): String? {
        val supportedAbis = android.os.Build.SUPPORTED_ABIS
        return when {
            supportedAbis.any { it == "arm64-v8a" }    -> "tap_inject_arm64"
            supportedAbis.any { it.startsWith("armeabi") } -> "tap_inject_arm32"
            else -> null
        }
    }

    // ── Root shell ────────────────────────────────────────────────────────────

    private fun openRootShell(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec("su")
            suProcess = proc
            suStream = DataOutputStream(proc.outputStream)
            Log.i(TAG, "Root shell opened")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open root shell: ${e.message}")
            false
        }
    }

    // ── Cache management ──────────────────────────────────────────────────────

    /** Force re-detection of touch node (call if device changes). */
    fun resetNodeCache() {
        prefs.edit().remove(PREF_EVENT_NODE).apply()
        eventNode = null
    }

    companion object {
        private const val TAG = "RootTapInjector"
        private const val PREF_EVENT_NODE = "event_node"
    }
}
