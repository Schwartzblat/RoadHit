package com.roadhit.debug

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A lab hook: receives the intent's string extras (except `run`), returns a short result. */
typealias LabHook = suspend (args: Map<String, String>) -> String

/**
 * Debug-only lab.
 *
 *   adb shell am start -n com.roadhit/.debug.LabActivity --es run <name> [--es k v ...]
 *
 * Each hook receives every string extra (except `run`) as [args] and returns a short
 * result string, logged as `RoadHit: lab <name> <result>`. To add a hook, add its entry
 * (or a map of them from its own file) to [hooks].
 *
 * Opened without `run` (from the "RoadHit Lab" launcher entry) it shows the voice
 * test-set recorder.
 */
class LabActivity : ComponentActivity() {

    private val hooks: Map<String, LabHook> = mapOf<String, LabHook>(
        "ping" to { _ -> "ok" },
    ) + speechHooks(this) + audioHooks(this) + gameHooks(this) + sampleHooks(this)

    private var showingRecorder = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-run a hook only on a fresh start; the recorder is shown again after recreation.
        if (savedInstanceState == null || intent.getStringExtra(EXTRA_RUN) == null) run(intent.extras)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        run(intent.extras)
    }

    private fun run(extras: Bundle?) {
        val name = extras?.getString(EXTRA_RUN)
        if (name == null) {
            showRecorder()
            return
        }
        val hook = hooks[name]
        if (hook == null) {
            Log.w(TAG, "lab $name unknown-hook; hooks: ${hooks.keys.joinToString()}")
            return
        }
        val args = extras.keySet()
            .filter { it != EXTRA_RUN }
            .mapNotNull { key -> extras.getString(key)?.let { key to it } }
            .toMap()
        lifecycleScope.launch {
            val result = try {
                hook(args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "lab $name failed", e)
                "error ${e.javaClass.simpleName}: ${e.message}"
            }
            Log.i(TAG, "lab $name $result")
        }
    }

    private fun showRecorder() {
        if (showingRecorder) return
        showingRecorder = true
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { RecorderScreen() }
            }
        }
    }

    private companion object {
        const val TAG = "RoadHit"
        const val EXTRA_RUN = "run"
    }
}
