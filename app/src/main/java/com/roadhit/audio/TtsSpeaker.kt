package com.roadhit.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.roadhit.game.Lang
import com.roadhit.game.Speaker
import com.roadhit.game.Utterance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Speaks with the platform text-to-speech engine (the default one), as media/speech so in
 * the car it comes out of the media channel. Does not request audio focus.
 *
 * The engine starts on first use and lives until [shutdown]. A language without an
 * installed voice is spoken with the other language's voice rather than not at all.
 *
 * Every wait on the engine is bounded, so [say] always returns: an engine that dies or
 * stops reporting progress is dropped, and the next [say] starts a fresh one.
 */
class TtsSpeaker(
    context: Context,
    /** How long the engine may take to start before [say] gives up (and retries next time). */
    private val initTimeoutMs: Long = 10_000,
    /** An utterance may take this long plus [perCharTimeoutMs] per character before it is abandoned. */
    private val utteranceTimeoutMs: Long = 10_000,
    private val perCharTimeoutMs: Long = 150,
) : Speaker {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val beeper = Beeper()

    /** One [say] at a time (they share the engine's queue); also keeps the engine from starting twice. */
    private val speaking = Mutex()

    // Main thread only.
    private var engine: TextToSpeech? = null
    /** Per language: the locale to use for it, or null when it has no voice. */
    private val voices = mutableMapOf<Lang, Locale?>()
    /** Languages already reported as missing (logged once per speaker, across engine restarts). */
    private val reportedMissing = mutableSetOf<Lang>()
    private var nextId = 0L

    /** Utterances waiting for the engine to finish them, by utterance id. */
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    override suspend fun say(parts: List<Utterance>) {
        val items = parts.filter { it.text.isNotBlank() }
        if (items.isEmpty()) return
        speaking.withLock {
            withContext(Dispatchers.Main.immediate) {
                // One at a time, so a stuck utterance can be abandoned without losing the rest;
                // after a dropped engine the next utterance starts a fresh one.
                for (u in items) {
                    val tts = engine() ?: return@withContext
                    if (!speakOne(tts, u)) dropEngine(tts)
                }
            }
        }
    }

    /** Main thread: speaks [u] and waits for it; false when the engine misbehaved. */
    private suspend fun speakOne(tts: TextToSpeech, u: Utterance): Boolean {
        val id = "sg-${nextId++}"
        val done = CompletableDeferred<Unit>()
        waiting[id] = done
        try {
            localeFor(tts, u.lang)?.let { tts.language = it }
            Log.i(TAG, "tts speak ${u.lang.name.lowercase()} voice=${tts.voice?.name}")
            if (tts.speak(u.text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                Log.w(TAG, "tts speak-failed")
                return false
            }
            val limit = utteranceTimeoutMs + perCharTimeoutMs * u.text.length
            if (withTimeoutOrNull(limit) { done.await() } == null) {
                Log.w(TAG, "tts timeout")
                tts.stop()
                return false
            }
            return true
        } finally {
            // Cancelled: nothing of ours keeps speaking.
            if (!done.isCompleted) tts.stop()
            waiting.remove(id)
        }
    }

    /** Main thread: forgets [tts] so the next [say] starts a fresh engine. */
    private fun dropEngine(tts: TextToSpeech) {
        if (engine !== tts) return
        engine = null
        voices.clear()
        tts.shutdown()
    }

    override suspend fun beep() = beeper.beep()

    /** Whether [lang] has a voice of its own (otherwise it is spoken with the other language's voice). */
    suspend fun hasVoice(lang: Lang): Boolean = speaking.withLock {
        withContext(Dispatchers.Main.immediate) {
            val tts = engine() ?: return@withContext false
            resolve(tts, lang) != null
        }
    }

    /** Stops speaking and frees the engine. Safe from any thread. */
    fun shutdown() {
        main.post {
            engine?.run {
                stop()
                shutdown()
            }
            engine = null
            voices.clear()
            waiting.values.forEach { it.complete(Unit) }
        }
    }

    /** Main thread: the started engine, or null if it failed to start. */
    private suspend fun engine(): TextToSpeech? {
        engine?.let { return it }
        var created: TextToSpeech? = null
        // On timeout or cancellation the half-started engine is shut down.
        val status = withTimeoutOrNull(initTimeoutMs) {
            suspendCancellableCoroutine { cont ->
                val tts = TextToSpeech(appContext) { status -> if (cont.isActive) cont.resume(status) }
                created = tts
                cont.invokeOnCancellation { main.post { tts.shutdown() } }
            }
        }
        if (status == null) {
            Log.w(TAG, "tts init-timeout")
            return null
        }
        val tts = created!!
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "tts init-failed $status")
            tts.shutdown()
            return null
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "tts error $errorCode")
                finished(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        Log.i(TAG, "tts engine ${tts.defaultEngine}")
        engine = tts
        return tts
    }

    private fun finished(utteranceId: String?) {
        utteranceId?.let { waiting[it]?.complete(Unit) }
    }

    /** Main thread: the locale to speak [lang] with, falling back to the other language's voice. */
    private fun localeFor(tts: TextToSpeech, lang: Lang): Locale? {
        resolve(tts, lang)?.let { return it }
        val other = if (lang == Lang.HE) Lang.EN else Lang.HE
        return resolve(tts, other)
    }

    /** Main thread: [lang]'s own locale if the engine has a voice for it, else null (cached). */
    private fun resolve(tts: TextToSpeech, lang: Lang): Locale? {
        if (lang in voices) return voices[lang]
        val candidates = when (lang) {
            Lang.HE -> listOf(Locale.forLanguageTag("he-IL"), Locale.forLanguageTag("iw-IL"))
            Lang.EN -> listOf(Locale.US)
        }
        val found = candidates.firstOrNull { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        voices[lang] = found
        val code = lang.name.lowercase()
        if (found == null) {
            if (reportedMissing.add(lang)) Log.w(TAG, "tts missing-voice $code")
        } else {
            tts.language = found
            val voice = tts.voice
            Log.i(TAG, "tts voice $code ${voice?.name} network=${voice?.isNetworkConnectionRequired} engine ${tts.defaultEngine}")
        }
        return found
    }

    private companion object {
        const val TAG = "RoadHit"
    }
}
