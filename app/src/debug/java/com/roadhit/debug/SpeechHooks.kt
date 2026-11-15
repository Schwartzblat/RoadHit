package com.roadhit.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.roadhit.game.Heard
import com.roadhit.game.Lang
import com.roadhit.speech.AudioFeed
import com.roadhit.speech.BytesMicStream
import com.roadhit.speech.GoogleListener
import com.roadhit.speech.MicSource
import com.roadhit.speech.MicStream
import com.roadhit.speech.PhoneMicSource
import com.roadhit.speech.SessionEnd
import com.roadhit.speech.Wav
import com.roadhit.speech.runRecognizerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "RoadHit"

/**
 * Speech lab hooks (all files live in [TestSet.dir]):
 *
 *   recognize --es file NN.wav --es lang he-IL|en-US [--es recognizer default|on-device]
 *       Feeds the WAV's PCM to a SpeechRecognizer through EXTRA_AUDIO_SOURCE. Without
 *       `recognizer`, tries the default service and falls back to the on-device one.
 *       Result: `NN <lang> <json array of alternatives>` or `NN <lang> error <code>`.
 *   synth --es text "<text>" --es lang he-IL|en-US --es file NAME.wav
 *       Speaks [text] with TextToSpeech into a 16 kHz mono 16-bit WAV.
 *   listen --es lang he|en [--es seconds 12] [--es path injected|direct] [--es endSilenceMs 2000]
 *       One answer from the phone's microphone through GoogleListener.
 *       Result: `Speech <json> audio=<ms>ms | Silence | Failed <reason>`, then `<elapsed>ms`.
 *   listen-file --es file NN.wav --es lang he|en [--es cut 1] [--es endSilenceMs 2000]
 *       Plays the WAV (then endSilenceMs + 2 s of silence) as a real-time microphone into GoogleListener,
 *       then relistens to the kept audio in the other language.
 *       Result: `NN <lang> <json> | relisten <other> <json>`, or `NN <lang> Silence|Failed <reason>`.
 *       `cut 1` ends the microphone after one second (expect `Failed mic-lost`).
 *       `endSilenceMs` (both hooks) is the quiet after the last recognized text that ends the answer.
 */
fun speechHooks(context: Context): Map<String, LabHook> = mapOf(
    "recognize" to { args -> recognize(context, args) },
    "synth" to { args -> synth(context, args) },
    "listen" to { args -> listen(context, args) },
    "listen-file" to { args -> listenFile(context, args) },
)

private enum class Recognizer(val label: String) { DEFAULT("default"), ON_DEVICE("on-device") }

private suspend fun recognize(context: Context, args: Map<String, String>): String {
    val name = args.require("file")
    val lang = args.require("lang")
    val attempts = when (args["recognizer"]) {
        null -> Recognizer.entries
        else -> listOf(
            Recognizer.entries.firstOrNull { it.label == args["recognizer"] }
                ?: throw IllegalArgumentException("--es recognizer must be default or on-device"),
        )
    }
    val pcm = withContext(Dispatchers.IO) { Wav.read(TestSet.file(context, name)) }
    require(pcm.sampleRate == Wav.TEST_SET_RATE && pcm.channels == 1) {
        "$name: need 16 kHz mono, got ${pcm.sampleRate} Hz x ${pcm.channels}"
    }
    val id = name.removeSuffix(".wav")
    var outcome = ""
    for (recognizer in attempts) {
        outcome = recognizeOnce(context, recognizer, pcm.data, lang)
        Log.i(TAG, "lab recognize-attempt $id $lang ${recognizer.label} $outcome")
        if (!outcome.startsWith("error")) break
    }
    return "$id $lang $outcome"
}

/** Returns a JSON array of alternatives, or `error <code>`. Must not run concurrently with itself. */
private suspend fun recognizeOnce(context: Context, which: Recognizer, pcm: ByteArray, lang: String): String {
    // Trailing silence lets the recognizer's end-of-speech detector fire before the pipe closes.
    val audio = pcm + ByteArray(TRAILING_SILENCE_BYTES)
    val end = runRecognizerSession(
        context,
        lang,
        AudioFeed(BytesMicStream(audio), audio.size),
        RECOGNIZE_TIMEOUT_MS,
        onDevice = which == Recognizer.ON_DEVICE,
    )
    return when (end) {
        is SessionEnd.Results -> {
            if (end.fromPartial) Log.d(TAG, "lab recognize onResults empty; using last partial")
            json(end.alternatives)
        }
        is SessionEnd.Error -> "error ${end.code}"
        SessionEnd.Timeout -> "error timeout"
        SessionEnd.Unavailable -> "error unavailable"
        SessionEnd.MicLost -> "error mic-lost"
    }
}

/** `listen --es lang he|en [--es seconds 12] [--es path injected|direct]`: one answer from the phone's microphone. */
private suspend fun listen(context: Context, args: Map<String, String>): String {
    val lang = parseLang(args.require("lang"))
    val seconds = args["seconds"]?.toInt() ?: 12
    val direct = when (args["path"] ?: "injected") {
        "injected" -> false
        "direct" -> true
        else -> throw IllegalArgumentException("--es path must be injected or direct")
    }
    val listener = GoogleListener(context, { PhoneMicSource(context) }, direct, endSilenceMs(args))
    try {
        Log.i(TAG, "lab listen start ${lang.name.lowercase()} seconds=$seconds direct=$direct endSilenceMs=${endSilenceMs(args)}")
        val start = SystemClock.elapsedRealtime()
        val heard = listener.listen(lang, seconds)
        return "${labText(heard)} ${SystemClock.elapsedRealtime() - start}ms"
    } finally {
        listener.release()
    }
}

/**
 * `listen-file --es file NN.wav --es lang he|en [--es cut 1]`: listens to the WAV played as a
 * microphone in real time, then recognizes the kept audio again in the other language.
 * With `cut 1` the microphone ends after one second, as when the assistant takes it.
 */
private suspend fun listenFile(context: Context, args: Map<String, String>): String {
    val name = args.require("file")
    val lang = parseLang(args.require("lang"))
    val other = if (lang == Lang.HE) Lang.EN else Lang.HE
    val cut = args["cut"] == "1"
    val pcm = withContext(Dispatchers.IO) { Wav.read(TestSet.file(context, name)) }
    require(pcm.sampleRate == Wav.TEST_SET_RATE && pcm.channels == 1) {
        "$name: need 16 kHz mono, got ${pcm.sampleRate} Hz x ${pcm.channels}"
    }
    // A real microphone keeps delivering silence; the trailing silence must outlast the
    // listener's end-of-speech pause (plus the recognizer's lag) or the stream ends first.
    val silenceMs = endSilenceMs(args) + 2_000
    val audio = pcm.data + ByteArray((silenceMs * Wav.TEST_SET_RATE * 2 / 1000).toInt())
    val playable = if (cut) audio.copyOf(minOf(audio.size, Wav.TEST_SET_RATE * 2)) else audio
    val listener = GoogleListener(context, { WavMicSource(playable) }, endSilenceMs = endSilenceMs(args))
    val id = name.removeSuffix(".wav")
    try {
        val start = SystemClock.elapsedRealtime()
        val heard = listener.listen(lang, 12)
        Log.i(TAG, "lab listen-file-heard $id ${labText(heard)} ${SystemClock.elapsedRealtime() - start}ms")
        if (heard !is Heard.Speech) return "$id ${lang.short} ${labText(heard)}"
        val again = listener.relisten(heard.audio!!, other)
        return "$id ${lang.short} ${json(heard.transcripts)} | relisten ${other.short} ${json(again)}"
    } finally {
        listener.release()
    }
}

/**
 * Plays [pcm] as a microphone: 20 ms chunks paced by the clock, then -1, or with [thenSilence]
 * silence until closed (as a real microphone would).
 */
internal class WavMicSource(private val pcm: ByteArray, private val thenSilence: Boolean = false) : MicSource {
    override fun open(): MicStream = object : MicStream {
        private val start = SystemClock.elapsedRealtime()
        private var pos = 0
        private var chunk = 0

        @Volatile
        private var closed = false

        override fun read(buf: ByteArray): Int {
            val due = start + chunk * CHUNK_MS
            while (!closed && SystemClock.elapsedRealtime() < due) Thread.sleep(2)
            if (closed) return -1
            chunk++
            if (pos >= pcm.size) {
                if (!thenSilence) return -1
                val n = minOf(buf.size, CHUNK_BYTES)
                buf.fill(0, 0, n)
                return n
            }
            val n = minOf(buf.size, CHUNK_BYTES, pcm.size - pos)
            pcm.copyInto(buf, 0, pos, pos + n)
            pos += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val CHUNK_MS = 20L
        const val CHUNK_BYTES = Wav.TEST_SET_RATE * 2 / 1000 * CHUNK_MS.toInt()
    }
}

private fun labText(heard: Heard): String = when (heard) {
    is Heard.Speech -> "Speech ${json(heard.transcripts)} audio=${(heard.audio?.pcm?.size ?: 0) / (Wav.TEST_SET_RATE * 2 / 1000)}ms"
    Heard.Silence -> "Silence"
    is Heard.Unclear -> "Unclear audio=${heard.audio.pcm.size / (Wav.TEST_SET_RATE * 2 / 1000)}ms"
    is Heard.Failed -> "Failed ${heard.reason}"
}

/** `--es endSilenceMs N` overrides the listener's end-of-speech pause. */
private fun endSilenceMs(args: Map<String, String>): Long =
    args["endSilenceMs"]?.toLong() ?: GoogleListener.DEFAULT_END_SILENCE_MS

private fun parseLang(value: String): Lang = when (value) {
    "he", "he-IL" -> Lang.HE
    "en", "en-US" -> Lang.EN
    else -> throw IllegalArgumentException("--es lang must be he or en")
}

private val Lang.short get() = name.lowercase()

private fun json(alternatives: List<String>) = JsonArray(alternatives.map(::JsonPrimitive)).toString()

internal suspend fun synth(context: Context, args: Map<String, String>): String {
    val text = args.require("text")
    val lang = args.require("lang")
    val name = args.require("file")
    val tts = startTts(context) ?: return "error tts-init"
    try {
        val langResult = tts.setLanguage(Locale.forLanguageTag(lang))
        if (langResult < TextToSpeech.LANG_AVAILABLE) return "$name error language $lang $langResult"
        val raw = File(context.cacheDir, "synth-raw.wav")
        val done = withTimeoutOrNull(SYNTH_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (cont.isActive) cont.resume(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (cont.isActive) cont.resume(false)
                    }
                })
                if (tts.synthesizeToFile(text, Bundle(), raw, "lab-synth") != TextToSpeech.SUCCESS && cont.isActive) {
                    cont.resume(false)
                }
            }
        }
        if (done != true) return "$name error synthesis"
        val (sourceRate, millis) = withContext(Dispatchers.IO) {
            val pcm = Wav.read(raw)
            val out = Wav.toTestSetFormat(pcm)
            Wav.write(TestSet.file(context, name), out)
            raw.delete()
            pcm.sampleRate to out.size / (Wav.TEST_SET_RATE * 2 / 1000)
        }
        return "$name ${sourceRate}Hz->${Wav.TEST_SET_RATE}Hz ${millis}ms"
    } finally {
        tts.shutdown()
    }
}

/** Starts the default TTS engine; null if it fails to initialize. */
private suspend fun startTts(context: Context): TextToSpeech? = withContext(Dispatchers.Main) {
    var tts: TextToSpeech? = null
    val status = suspendCancellableCoroutine { cont ->
        tts = TextToSpeech(context) { status -> if (cont.isActive) cont.resume(status) }
    }
    if (status == TextToSpeech.SUCCESS) tts else null.also { tts?.shutdown() }
}

private fun Map<String, String>.require(key: String): String =
    this[key] ?: throw IllegalArgumentException("missing --es $key")

private const val RECOGNIZE_TIMEOUT_MS = 30_000L
private const val SYNTH_TIMEOUT_MS = 30_000L
private const val TRAILING_SILENCE_BYTES = Wav.TEST_SET_RATE * 2 * 2 // two seconds
