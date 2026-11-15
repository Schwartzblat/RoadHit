package com.roadhit.debug

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.roadhit.audio.DeezerPreviews
import com.roadhit.audio.ExoClipPlayer
import com.roadhit.audio.TtsSpeaker
import com.roadhit.game.Genre
import com.roadhit.game.Lang
import com.roadhit.game.Names
import com.roadhit.game.Origin
import com.roadhit.game.Song
import com.roadhit.game.Utterance
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private const val TAG = "RoadHit"

/**
 * Audio lab hooks:
 *
 *   clip --es id <deezerId> [--es seconds 5] [--es start-timeout-ms N] [--es stall-timeout-ms N]
 *       Plays the track's preview for up to `seconds`. Result: `true|false <elapsed ms>`.
 *   clip-stop --es id <deezerId> [--es after 2]
 *       Starts the clip and calls stop() after `after` seconds. Result: `<play result> <elapsed ms>`.
 *   say [--es he "<text>"] [--es en "<text>"] [--es timeout-ms N] [--es per-char-ms N] [--es init-timeout-ms N]
 *       Speaks the Hebrew text, then the English one. Result: `ok <elapsed ms>`, or
 *       `missing-voice he|en <elapsed ms>`. The timeouts override TtsSpeaker's defaults.
 *   beep
 *       Plays the listening cue. Result: `ok`.
 */
fun audioHooks(context: Context): Map<String, LabHook> = mapOf(
    "clip" to { args -> clip(context, args) },
    "clip-stop" to { args -> clipStop(context, args) },
    "say" to { args -> say(context, args) },
    "beep" to { _ -> beep(context) },
)

private suspend fun clip(context: Context, args: Map<String, String>): String {
    val song = labSong(args)
    val seconds = args["seconds"]?.toInt() ?: 5
    val player = ExoClipPlayer(
        context,
        DeezerPreviews(),
        startTimeoutMs = args["start-timeout-ms"]?.toLong() ?: 20_000,
        stallTimeoutMs = args["stall-timeout-ms"]?.toLong() ?: 10_000,
    )
    try {
        Log.i(TAG, "lab clip start deezer=${song.deezerId} seconds=$seconds")
        val start = SystemClock.elapsedRealtime()
        val result = player.play(song, seconds)
        return "$result ${SystemClock.elapsedRealtime() - start}"
    } finally {
        player.release()
    }
}

private suspend fun clipStop(context: Context, args: Map<String, String>): String = coroutineScope {
    val song = labSong(args)
    val after = args["after"]?.toDouble() ?: 2.0
    val player = ExoClipPlayer(context, DeezerPreviews())
    try {
        val start = SystemClock.elapsedRealtime()
        Log.i(TAG, "lab clip-stop start deezer=${song.deezerId} after=$after")
        val result = async { player.play(song, maxSeconds = 30) }
        delay((after * 1000).toLong())
        player.stop()
        val played = result.await()
        "$played ${SystemClock.elapsedRealtime() - start}"
    } finally {
        player.release()
    }
}

private suspend fun say(context: Context, args: Map<String, String>): String {
    val parts = listOfNotNull(
        args["he"]?.let { Utterance(it, Lang.HE) },
        args["en"]?.let { Utterance(it, Lang.EN) },
    )
    require(parts.isNotEmpty()) { "give --es he and/or --es en" }
    val speaker = TtsSpeaker(
        context,
        initTimeoutMs = args["init-timeout-ms"]?.toLong() ?: 10_000,
        utteranceTimeoutMs = args["timeout-ms"]?.toLong() ?: 10_000,
        perCharTimeoutMs = args["per-char-ms"]?.toLong() ?: 150,
    )
    try {
        Log.i(TAG, "lab say start")
        val start = SystemClock.elapsedRealtime()
        speaker.say(parts)
        val elapsed = SystemClock.elapsedRealtime() - start
        val missing = parts.map { it.lang }.distinct().filterNot { speaker.hasVoice(it) }
        return if (missing.isEmpty()) "ok $elapsed" else "missing-voice " + missing.joinToString(" ") { it.name.lowercase() } + " $elapsed"
    } finally {
        speaker.shutdown()
    }
}

private suspend fun beep(context: Context): String {
    val speaker = TtsSpeaker(context)
    try {
        speaker.beep()
        return "ok"
    } finally {
        speaker.shutdown()
    }
}

/** A stand-in song around a Deezer id; only [Song.deezerId] matters to the clip player. */
private fun labSong(args: Map<String, String>): Song {
    val id = args["id"]?.toLong() ?: throw IllegalArgumentException("missing --es id")
    return Song(
        id = "lab-$id",
        title = Names("", ""),
        artist = Names("", ""),
        script = Lang.EN,
        year = 0,
        origin = Origin.INTL,
        genre = Genre.OTHER,
        deezerId = id,
        rank = 0,
    )
}
