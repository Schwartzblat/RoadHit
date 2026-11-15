package com.roadhit.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession as PlatformMediaSession
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.roadhit.R
import com.roadhit.car.CarCloseAction
import com.roadhit.car.MediaPlayAction
import com.roadhit.car.TaskRemovedAction
import com.roadhit.car.carCloseAction
import com.roadhit.car.mediaPlayAction
import com.roadhit.car.taskRemovedAction
import com.roadhit.game.Button
import com.roadhit.game.Display
import com.roadhit.game.GameView
import com.roadhit.game.Phase
import com.roadhit.game.Song
import com.roadhit.speech.MicSource
import com.roadhit.speech.PhoneMicSource
import com.roadhit.ui.MainActivity
import com.roadhit.ui.PhoneUi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the game: a foreground media service whose session ([GamePlayer]) is what the
 * notification, the media buttons and Android Auto see. [start] runs one game ([GameEngine.run])
 * while holding audio focus; [stop], a permanent focus loss or the game's end stops the service.
 * The engine saves at turn boundaries, so a stopped game can be continued on the next start.
 *
 * Foreground types: mediaPlayback and microphone (so the phone microphone works with the screen
 * off). Started from the car session, Android grants both while the Android Auto host has the car
 * app bound (see the plan's Decision Log); whenever Android refuses the microphone type the service
 * runs as mediaPlayback only (in the car the microphone comes through [carMic]).
 *
 * Android Auto disconnecting stops the game (it stays saved), also when the phone screen shows the
 * app: the car session's end does that, the permanent audio-focus loss Android Auto causes as it
 * leaves does too, and so does this service watching the projection ([CarConnection]), for a game
 * the host started through the media session with no car screen opened.
 */
class GameService : MediaLibraryService() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var session: MediaLibrarySession
    private lateinit var player: GamePlayer
    private lateinit var focus: GameFocus

    // Main thread only.
    private var components: GameComponents? = null
    private var componentsLoad: Deferred<GameComponents?>? = null
    private var run: Job? = null
    private var runState = RunState.IDLE
    private var rig: GameRig? = null
    /** Whether the foreground types include the microphone (false once Android refused it). */
    private var micWanted = false
    /** The last notification Media3 built, reused when a start arrives while running. */
    private var lastNotification: MediaNotification? = null
    private var pausedByFocus = false
    private var destroyed = false
    private lateinit var carConnection: LiveData<Int>
    private var projected = false

    private val carConnectionObserver = Observer<Int> { type ->
        val now = type == CarConnection.CONNECTION_TYPE_PROJECTION
        if (projected && !now) onCarDisconnected()
        projected = now
    }

    private val display = object : Display {
        override fun show(view: GameView) {
            if (_view.value.phase != view.phase) {
                Log.i(TAG, "game view ${view.phase} player=${view.currentPlayer} round=${view.round}/${view.rounds} scores=${view.scores}")
            }
            _view.value = view
            player.show(view)
        }
    }

    // onCreate runs on the main thread. Lint pairs the main-thread observer below with the
    // @WorkerThread Bitmap.compress inside renderLogo (one small PNG, rendered once) and flags them.
    @SuppressLint("ThreadConstraint")
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val fallbackLang = defaultLang(this)
        componentsLoad = scope.async {
            try {
                withContext(Dispatchers.IO) { GameComponents.load(applicationContext) }.also { components = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "game components failed to load", e)
                null
            }
        }
        player = GamePlayer(
            lang = { components?.stores?.prefs?.value?.lang ?: fallbackLang },
            logo = renderLogo(this),
            coverFor = { song -> components?.previews?.coverFor(song.deezerId) },
            controls = object : GamePlayer.Controls {
                override fun play() = onPlayCommand()
                override fun stop() = stopGame()
                override fun press(b: Button) = press(b, fromUser = true)
            },
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, GameLibraryCallback()).setSessionActivity(openApp).build()
        ensureChannel()
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel)
            .setNotificationId(NOTIFICATION_ID)
            .build()
            .apply { setSmallIcon(R.drawable.ic_notification) }
        setMediaNotificationProvider(TypeKeepingProvider(provider))
        // Started with startForegroundService and no controller, Media3 would not know the session.
        addSession(session)
        focus = GameFocus(getSystemService(AudioManager::class.java), main, ::onFocusAction)
        instance = this
        _sessionToken.value = session.platformToken
        watchCarConnection()
    }

    @MainThread
    private fun watchCarConnection() {
        carConnection = CarConnection(this).type.also { it.observeForever(carConnectionObserver) }
    }

    /**
     * Main thread: a play command through the media session. It starts a game only while RoadHit
     * is open in the car or on the phone ([mediaPlayAction]), and with the microphone permission:
     * the phone and car screens ask for it before they start a game; here there is no one to ask.
     */
    private fun onPlayCommand() {
        when (mediaPlayAction(_view.value.phase, carSessionOpen = carMic != null, phoneUiVisible = PhoneUi.visible)) {
            MediaPlayAction.RESUME -> press(Button.PLAY, fromUser = true)
            MediaPlayAction.IGNORE -> Log.i(TAG, "play ignored: app not open")
            MediaPlayAction.START_GAME ->
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    start(this)
                } else {
                    Log.i(TAG, "game not started from the media session: no microphone permission")
                }
        }
    }

    /** Main thread: Android Auto stopped projecting. */
    private fun onCarDisconnected() {
        val action = carCloseAction(_view.value.phase, PhoneUi.visible)
        Log.i(TAG, "car disconnected: $action")
        if (action == CarCloseAction.STOP_GAME && runState != RunState.IDLE) stopGame()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_START) onStartRequested()
        // A killed game is not restarted by the system: the next start offers to continue it.
        return START_NOT_STICKY
    }

    /** Main thread. */
    private fun onStartRequested() {
        // startForegroundService needs startForeground unless the service is already in the
        // foreground; a running game's foreground (and its microphone type) is left alone.
        if (foregroundServiceType == 0 && !enterForeground()) {
            if (runState == RunState.IDLE) stopSelf()
            return
        }
        on(RunEvent.START)
    }

    /** Main thread. Runs [event] through the run lifecycle ([runTransition]) and carries out its effect. */
    private fun on(event: RunEvent) {
        val t = runTransition(runState, event)
        runState = t.state
        if (destroyed) return
        when (t.effect) {
            RunEffect.LAUNCH -> launchRun()
            RunEffect.IGNORE -> Log.i(TAG, "game start ignored: a game is running")
            RunEffect.PEND -> Log.i(TAG, "game start pending: the previous game is ending")
            RunEffect.CANCEL_RUN -> {
                Log.i(TAG, "game stop requested")
                run?.cancel()
            }
            RunEffect.STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "game service stopping")
            }
            RunEffect.NONE -> Unit
        }
    }

    /** Main thread. */
    private fun launchRun() {
        if (foregroundServiceType == 0 && !enterForeground()) {
            Log.w(TAG, "game not started: no foreground")
            runState = RunState.IDLE // nothing was launched, so there is no run to end
            stopSelf()
            return
        }
        display.show(IDLE_VIEW.copy(phase = Phase.SETUP)) // keeps Media3's notification in the foreground
        run = scope.launch {
            try {
                val loaded = componentsLoad?.await()
                when {
                    loaded == null || loaded.catalog.songs.isEmpty() -> Log.w(TAG, "game not started: the catalog is empty")
                    !focus.request() -> Log.w(TAG, "game not started: audio focus refused")
                    else -> {
                        val rig = GameRig(applicationContext, loaded, display, ::currentMic) { song ->
                            debugClipObserver?.invoke(song)
                        }.also { rig = it }
                        Log.i(TAG, "game run start")
                        withContext(Dispatchers.Default) { rig.engine.run() }
                        Log.i(TAG, "game run ended")
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "game run cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "game run failed", e)
            } finally {
                withContext(NonCancellable) {
                    on(RunEvent.RUN_ENDING)
                    cleanUpRun()
                    on(RunEvent.RUN_ENDED)
                }
            }
        }
    }

    /** After a run (finished, abandoned, refused or cancelled): release and save. */
    private suspend fun cleanUpRun() {
        focus.abandon()
        pausedByFocus = false
        rig?.release()
        rig = null
        lastNotification = null // a later start must not re-post this run's (reveal) notification
        if (_view.value.phase != Phase.IDLE && _view.value.phase != Phase.FINISHED) display.show(IDLE_VIEW)
        components?.stores?.let { stores -> withTimeoutOrNull(FLUSH_TIMEOUT_MS) { stores.flush() } }
        run = null
        Log.i(TAG, "game run cleaned up")
    }

    /** Main thread. Cancels the run (the saved game stays, a pending start is dropped); the run's end stops the service. */
    private fun stopGame() = on(RunEvent.STOP)

    /** Main thread. */
    private fun press(b: Button, fromUser: Boolean) {
        if (fromUser) pausedByFocus = false
        rig?.engine?.onButton(b)
    }

    private fun currentMic(): MicSource? = carMic ?: debugMic?.invoke() ?: PhoneMicSource(this)

    /** Main thread. */
    private fun onFocusAction(action: FocusAction) {
        if (rig == null) return
        when (action) {
            FocusAction.PAUSE -> if (player.phase != Phase.PAUSED && !pausedByFocus) {
                pausedByFocus = true
                press(Button.PAUSE, fromUser = false)
            }
            FocusAction.RESUME -> if (pausedByFocus) {
                pausedByFocus = false
                press(Button.PLAY, fromUser = false)
            }
            FocusAction.STOP -> {
                Log.i(TAG, "audio focus lost: stopping the game")
                stopGame()
            }
            FocusAction.NONE -> Unit
        }
    }

    /**
     * Main thread. Foreground with both types if Android allows the microphone, else mediaPlayback
     * only. Uses Media3's notification when there is one, else a placeholder it soon replaces.
     */
    private fun enterForeground(): Boolean {
        val notification = lastNotification?.notification ?: placeholderNotification()
        try {
            startForeground(NOTIFICATION_ID, notification, BOTH_TYPES)
            micWanted = true
            return true
        } catch (e: SecurityException) {
            Log.i(TAG, "fgs microphone type unavailable")
        } catch (e: IllegalStateException) {
            Log.i(TAG, "fgs microphone type unavailable")
        }
        micWanted = false
        return try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "game service may not enter the foreground", e)
            false
        }
    }

    /**
     * Media3 calls startForeground with mediaPlayback only, which drops the microphone type; this
     * puts it back right after (main thread), while the service is in the foreground.
     */
    private fun keepMicrophoneType(n: MediaNotification) {
        if (runState != RunState.RUNNING || destroyed) return
        lastNotification = n
        if (!micWanted) return
        val types = foregroundServiceType
        if (types == 0 || types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) return
        try {
            startForeground(n.notificationId, n.notification, BOTH_TYPES)
        } catch (e: RuntimeException) {
            micWanted = false
            Log.i(TAG, "fgs microphone type unavailable")
        }
    }

    @OptIn(UnstableApi::class)
    private inner class TypeKeepingProvider(private val inner: MediaNotification.Provider) :
        MediaNotification.Provider by inner {
        override fun createNotification(
            mediaSession: MediaSession,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification {
            val n = inner.createNotification(mediaSession, mediaButtonPreferences, actionFactory) { updated ->
                onNotificationChangedCallback.onNotificationChanged(updated)
                afterMedia3Posts(updated)
            }
            afterMedia3Posts(n)
            return n
        }

        /** Media3 posts the notification on the main thread after this; run after that. */
        private fun afterMedia3Posts(n: MediaNotification) {
            main.post { main.post { keepMicrophoneType(n) } }
        }
    }

    /**
     * While a game runs the service stays in the foreground, paused or not. With no game there is no
     * notification: the idle and finished player is a valid ended item (for the car's card), which
     * Media3 would otherwise keep as a notification while the car holds the service bound.
     */
    @OptIn(UnstableApi::class)
    override fun onUpdateNotificationAsync(session: MediaSession, startInForegroundRequired: Boolean): ListenableFuture<Void?> {
        if (runState == RunState.IDLE) {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            return Futures.immediateVoidFuture()
        }
        return super.onUpdateNotificationAsync(session, true)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun placeholderNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(APP_TITLE)
        .setOngoing(true)
        .build()

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Ruling R39: the car can still control a game; on the phone alone swiping away stops it.
        if (runState == RunState.RUNNING && taskRemovedAction(_view.value.phase, carMic != null) == TaskRemovedAction.KEEP_RUNNING) {
            Log.i(TAG, "task removed: the game keeps running for the car")
            return
        }
        Log.i(TAG, "task removed: stopping")
        stopGame()
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        destroyed = true
        instance = null
        carConnection.removeObserver(carConnectionObserver)
        _sessionToken.value = null
        run?.cancel()
        focus.abandon()
        rig?.release()
        rig = null
        // Bounded: never hold the main thread for more than a moment.
        components?.stores?.let { stores -> runBlocking { withTimeoutOrNull(FLUSH_TIMEOUT_MS) { stores.flush() } } }
        scope.cancel() // the shared stores' scope is the process's, not the service's
        removeSession(session)
        session.release()
        player.release()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        Log.i(TAG, "game service destroyed")
        super.onDestroy()
    }

    companion object {
        /** The playable "RoadHit" item: request it (by this id) to start the game. */
        const val GAME_MEDIA_ID = "song-game"

        private const val TAG = "RoadHit"
        private const val APP_TITLE = "RoadHit"
        private const val ACTION_START = "com.roadhit.action.START_GAME"
        private const val CHANNEL_ID = "game"
        private const val NOTIFICATION_ID = 1001
        private const val FLUSH_TIMEOUT_MS = 500L
        private const val BOTH_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        private val _view = MutableStateFlow(IDLE_VIEW)

        /** What the game shows now; [Phase.IDLE] when no game runs. */
        val view: StateFlow<GameView> = _view

        private val _sessionToken = MutableStateFlow<PlatformMediaSession.Token?>(null)

        /** The platform token of the live session (for the car's MediaPlaybackManager); null while the service is not created. */
        val sessionToken: StateFlow<PlatformMediaSession.Token?> = _sessionToken

        /** The car's microphone while a car session is live; listens use it instead of the phone's. */
        @Volatile
        var carMic: MicSource? = null

        /** Tests only: called with each song as its clip starts (the debug lab logs its id for the anti-goal checks). */
        @Volatile
        var debugClipObserver: ((Song) -> Unit)? = null

        /** Tests only: consulted on every song pick; a catalog id it returns is played next (see [SongPicker]). */
        @Volatile
        var debugNextSong: (() -> String?)? = null

        /** Tests only: consulted on every listen; when it returns null the phone microphone is used. */
        @Volatile
        var debugMic: (() -> MicSource?)? = null

        @Volatile
        private var instance: GameService? = null

        /** Starts a game unless one is running. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, GameService::class.java).setAction(ACTION_START))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "game service could not be started", e)
            }
        }

        /** Stops the game (the saved game stays) and the service. */
        fun stop(context: Context) {
            Handler(context.mainLooper).post { instance?.stopGame() }
        }

        /** A media button, as if pressed on the notification (lab and tests). */
        internal fun press(b: Button) {
            Handler(Looper.getMainLooper()).post { instance?.press(b, fromUser = true) }
        }
    }
}

/** What a change of audio focus does to the game. */
internal enum class FocusAction { PAUSE, RESUME, STOP, NONE }

internal fun focusAction(change: Int): FocusAction = when (change) {
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusAction.PAUSE
    AudioManager.AUDIOFOCUS_GAIN -> FocusAction.RESUME
    AudioManager.AUDIOFOCUS_LOSS -> FocusAction.STOP
    else -> FocusAction.NONE
}

/** The game's audio focus (media, whole game). Changes arrive on [handler]'s thread. */
private class GameFocus(
    private val audio: AudioManager,
    handler: Handler,
    onAction: (FocusAction) -> Unit,
) {
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setWillPauseWhenDucked(true) // a "duck" pauses the game instead
        .setOnAudioFocusChangeListener({ change ->
            Log.i("RoadHit", "audio focus change $change")
            onAction(focusAction(change))
        }, handler)
        .build()

    private var held = false

    fun request(): Boolean {
        held = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    fun abandon() {
        if (!held) return
        held = false
        audio.abandonAudioFocusRequest(request)
    }
}
