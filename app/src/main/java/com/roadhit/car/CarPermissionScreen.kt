package com.roadhit.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.roadhit.game.Lang
import com.roadhit.ui.uiStringsFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal fun hasMicPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * Shown instead of the game when the microphone permission is missing: explains why it is needed
 * and asks for it on the phone ([CarContext.requestPermissions]). [onGranted] is called once, on
 * the main thread, when the permission is there, however it was granted; the screen it returns
 * then replaces this one.
 */
internal class CarPermissionScreen(
    carContext: CarContext,
    private val lang: StateFlow<Lang>,
    private val onGranted: () -> Screen,
) : Screen(carContext) {

    // Main thread.
    private var done = false

    init {
        lifecycleScope.launch { lang.drop(1).collect { invalidate() } }
        lifecycleScope.launch {
            // A grant in the phone's settings brings no callback: look once a second while on screen.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (!hasMicPermission(carContext)) delay(POLL_MS)
                granted()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val s = uiStringsFor(lang.value)
        val allow = Action.Builder()
            .setTitle(s.carMicAllow)
            .setFlags(Action.FLAG_PRIMARY)
            // The prompt appears on the phone, so only while parked.
            .setOnClickListener(ParkedOnlyOnClickListener.create(::request))
            .build()
        return MessageTemplate.Builder(s.carMicNeeded)
            .setHeader(Header.Builder().setTitle(s.appName).build())
            .addAction(allow)
            .build()
    }

    private fun request() {
        Log.i(TAG, "car asks for the microphone permission")
        carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
            if (Manifest.permission.RECORD_AUDIO in granted) granted() else Log.i(TAG, "car microphone permission refused")
        }
    }

    private fun granted() {
        if (done) return
        done = true
        Log.i(TAG, "car microphone permission granted")
        screenManager.push(onGranted())
        finish()
    }

    private companion object {
        const val TAG = "RoadHit"
        const val POLL_MS = 1000L
    }
}
