package com.roadhit.debug

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.roadhit.game.Button
import com.roadhit.game.Lang
import com.roadhit.session.GameService
import com.roadhit.session.appStores
import com.roadhit.speech.MicSource
import com.roadhit.speech.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "RoadHit"
internal const val LEAD_IN_BYTES = Wav.TEST_SET_RATE * 2 * 3 / 4 // 750 ms

/**
 * Game lab hooks:
 *
 *   game-start [--es delay N] | game-stop   GameService.start (N s later) / stop
 *   button --es b NEXT|PREVIOUS|PAUSE|PLAY
 *   dump                               the current GameView and the session's metadata title/artist
 *   dump-prefs                         stored prefs, last settings and the saved game
 *   set-prefs [--es lang HE|EN] [--es clip N] [--es tolerance N]   changes the stored prefs
 *   game-mic --es files "17,09,-,name" answers for the next listens, one per listen, from
 *                                      testset/<name>.wav played in real time after a 750 ms
 *                                      lead-in, then silence;
 *                                      "-" is a silent listen; when the queue is empty, silence
 *   game-mic --es clear 1              back to the phone microphone
 *   game-songs --es ids "a,b,c"        the next song picks are these catalog ids, in order, whatever
 *                                      the settings; then random picks again
 *   game-songs --es clear 1            random picks only
 *   focus-steal [--es seconds 3]       holds transient audio focus from another client (a "call")
 *
 * Registering these hooks (any LabActivity start) also installs the `debug song <id>` clip log.
 */
fun gameHooks(context: Context): Map<String, LabHook> {
    // Debug builds only: the song id of each clip, for the anti-goal checks (`debug song <id>`).
    GameService.debugClipObserver = { song -> Log.d(TAG, "debug song ${song.id}") }
    return gameHookMap(context)
}

private fun gameHookMap(context: Context): Map<String, LabHook> = mapOf(
    "game-start" to { args ->
        // `--es delay N`: start N seconds later, e.g. after HOME, as from the car (no visible activity).
        args["delay"]?.toLong()?.let { delay(it * 1000) }
        GameService.start(context)
        "ok"
    },
    "game-stop" to { _ -> GameService.stop(context); "ok" },
    "button" to { args ->
        val b = Button.valueOf(args["b"] ?: throw IllegalArgumentException("missing --es b"))
        GameService.press(b)
        b.name
    },
    "dump" to { _ -> dump(context) },
    "dump-prefs" to { _ -> dumpPrefs(context) },
    "set-prefs" to { args -> setPrefs(context, args) },
    "game-mic" to { args -> gameMic(context, args) },
    "game-songs" to { args -> gameSongs(context, args) },
    "focus-steal" to { args -> focusSteal(context, args) },
)

private suspend fun dump(context: Context): String {
    val v = GameService.view.value
    val view = "phase=${v.phase} players=${v.playerNames} scores=${v.scores} current=${v.currentPlayer} " +
        "round=${v.round}/${v.rounds} revealed=${v.revealed != null}"
    val meta = withTimeoutOrNull(5_000) { sessionMetadata(context) } ?: "unavailable"
    return "$view | $meta"
}

/** Reads the session's metadata through a MediaController, as the car and the notification see it. */
private suspend fun sessionMetadata(context: Context): String = withContext(Dispatchers.Main) {
    if (GameService.sessionToken.value == null) return@withContext "no session"
    val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, GameService::class.java)))
        .buildAsync()
    try {
        val controller = suspendCancellableCoroutine<MediaController?> { cont ->
            future.addListener({ cont.resume(runCatching { future.get() }.getOrNull()) }, ContextCompat.getMainExecutor(context))
        } ?: return@withContext "controller failed"
        val m = controller.mediaMetadata
        "title=${m.title} artist=${m.artist} playing=${controller.isPlaying} state=${controller.playbackState}"
    } finally {
        MediaController.releaseFuture(future)
    }
}

private suspend fun dumpPrefs(context: Context): String {
    val stores = context.appStores()
    return "prefs=${stores.prefs.value} settings=${stores.loadSettings()} game=${stores.loadGame()}"
}

private suspend fun setPrefs(context: Context, args: Map<String, String>): String {
    val stores = context.appStores()
    stores.updatePrefs { p ->
        p.copy(
            lang = args["lang"]?.let(Lang::valueOf) ?: p.lang,
            clipSeconds = args["clip"]?.toInt() ?: p.clipSeconds,
            yearTolerance = args["tolerance"]?.toInt() ?: p.yearTolerance,
        )
    }
    return "prefs=${stores.prefs.value}"
}

private fun gameMic(context: Context, args: Map<String, String>): String {
    if (args["clear"] == "1") {
        GameService.debugMic = null
        return "cleared"
    }
    val names = (args["files"] ?: throw IllegalArgumentException("missing --es files or --es clear 1"))
        .split(',').map { it.trim().removeSuffix(".wav") }.filter { it.isNotEmpty() }
    // Fail now on a missing file rather than mid-game.
    names.filter { it != "-" }.forEach { require(TestSet.file(context, "$it.wav").exists()) { "no testset/$it.wav" } }
    val queue = ArrayDeque(names)
    GameService.debugMic = {
        val name = synchronized(queue) { queue.removeFirstOrNull() }
        micFor(context, name, left = synchronized(queue) { queue.size })
    }
    return "queued ${names.joinToString(",")}"
}

private suspend fun gameSongs(context: Context, args: Map<String, String>): String {
    if (args["clear"] == "1") {
        GameService.debugNextSong = null
        return "cleared"
    }
    val ids = (args["ids"] ?: throw IllegalArgumentException("missing --es ids or --es clear 1"))
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    // Fail now on an unknown id rather than silently falling back to a random song mid-game.
    val catalog = labCatalog(context)
    ids.forEach { require(catalog.byId(it) != null) { "no song $it in the catalog" } }
    val queue = ArrayDeque(ids)
    GameService.debugNextSong = {
        synchronized(queue) { queue.removeFirstOrNull() }?.also { Log.i(TAG, "lab game-songs picks $it") }
    }
    return "queued ${ids.joinToString(",")}"
}

private fun micFor(context: Context, name: String?, left: Int): MicSource {
    if (name == null || name == "-") {
        Log.i(TAG, "lab game-mic silence (left=$left)")
        return WavMicSource(ByteArray(0), thenSilence = true)
    }
    val pcm = Wav.read(TestSet.file(context, "$name.wav"))
    Log.i(TAG, "lab game-mic plays $name (left=$left)")
    // A lead-in, as a player answers a moment after the beep: the recognizer misses a short word at t=0.
    return WavMicSource(ByteArray(LEAD_IN_BYTES) + pcm.data, thenSilence = true)
}

private suspend fun focusSteal(context: Context, args: Map<String, String>): String {
    val seconds = args["seconds"]?.toLong() ?: 3
    val audio = context.getSystemService(AudioManager::class.java)
    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()
    val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    Log.i(TAG, "lab focus-steal granted=$granted for ${seconds}s")
    try {
        delay(seconds * 1000)
    } finally {
        audio.abandonAudioFocusRequest(request)
    }
    return "released after ${seconds}s"
}
