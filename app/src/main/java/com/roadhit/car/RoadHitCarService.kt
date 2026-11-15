package com.roadhit.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.roadhit.game.Lang
import com.roadhit.session.GameService
import com.roadhit.session.appStores
import com.roadhit.session.defaultLang
import com.roadhit.speech.MicSource
import com.roadhit.ui.PhoneUi
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Android Auto entry point: the app presents itself to the car as a templated media app. */
class RoadHitCarService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = RoadHitCarSession()
}

/**
 * One car connection.
 *
 * Created: the car's microphone becomes the game's ([GameService.carMic]). Each time RoadHit
 * comes on the car screen, per [carOpenAction], a game starts (opening RoadHit in the car starts
 * it with no touch), a running one is shown, or the microphone permission is asked for first. While the playback screen is up,
 * the session stays connected to [GameService] and hands every session token it publishes to the
 * host, so the host's now-playing card and controls follow the game.
 *
 * Destroyed (the car disconnected): the car microphone is dropped and, per [carCloseAction], a
 * running game stops (it stays saved, to be continued), also when the phone screen shows the app.
 *
 * The game service is started straight from here: while the Android Auto host has the car app
 * bound, Android allows the foreground start, with the microphone type (see the plan's Decision Log).
 */
private class RoadHitCarSession : Session() {

    // Main thread.
    private var controller: ListenableFuture<MediaController>? = null
    private var mic: MicSource? = null

    override fun onCreateScreen(intent: Intent): Screen {
        Log.i(TAG, "car session created")
        current = this
        val app = carContext.applicationContext
        val lang = MutableStateFlow(defaultLang(app))
        lifecycleScope.launch {
            try {
                app.appStores().prefs.collect { lang.value = it.lang }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "car: the stores could not be opened", e)
            }
        }
        val carMic = CarMicSource(carContext).also { mic = it }
        GameService.carMic = carMic
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onCarAppOpened()
            override fun onDestroy(owner: LifecycleOwner) = onCarGone(app)
        })
        return if (carOpenAction(GameService.view.value.phase, hasMicPermission(carContext)) == CarOpenAction.ASK_PERMISSION) {
            CarPermissionScreen(carContext, lang) {
                onCarAppOpened()
                playbackScreen(lang)
            }
        } else {
            playbackScreen(lang)
        }
    }

    /**
     * Main thread: RoadHit came on the car screen (opened from the launcher, or returned to while
     * connected), or the microphone permission has just been granted.
     */
    private fun onCarAppOpened() {
        val action = carOpenAction(GameService.view.value.phase, hasMicPermission(carContext))
        Log.i(TAG, "car open: $action")
        if (action == CarOpenAction.START_GAME) startGame()
    }

    private fun startGame() {
        Log.i(TAG, "car start game")
        GameService.start(carContext)
    }

    /** The playback screen, and the connection to the game's media session it shows. */
    private fun playbackScreen(lang: MutableStateFlow<Lang>): Screen {
        connectToGameService()
        return GameCarScreen(carContext, lang)
    }

    private fun connectToGameService() {
        if (controller != null) return
        // Binding creates the service (and its session) if no game started it, and keeps it while the car is connected.
        val token = SessionToken(carContext, ComponentName(carContext, GameService::class.java))
        controller = MediaController.Builder(carContext, token).buildAsync()
        // A re-created service publishes a fresh token; register each one.
        lifecycleScope.launch {
            GameService.sessionToken.filterNotNull().collect { platformToken ->
                carContext.getCarService(MediaPlaybackManager::class.java)
                    .registerMediaPlaybackToken(MediaSessionCompat.Token.fromToken(platformToken))
                Log.i(TAG, "car media token registered")
            }
        }
    }

    /** Main thread, when the session is destroyed. */
    private fun onCarGone(app: Context) {
        controller?.let(MediaController::releaseFuture)
        controller = null
        if (GameService.carMic === mic) GameService.carMic = null
        mic = null
        if (current !== this) {
            Log.i(TAG, "car session destroyed after a newer one started: the game is left alone")
            return
        }
        current = null
        val action = carCloseAction(GameService.view.value.phase, PhoneUi.visible)
        Log.i(TAG, "car session destroyed: $action")
        if (action == CarCloseAction.STOP_GAME) GameService.stop(app)
    }

    companion object {
        private const val TAG = "RoadHit"

        /** The newest car session (main thread); an older one ending must not stop its game. */
        private var current: RoadHitCarSession? = null
    }
}
