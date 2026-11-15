package com.roadhit.speech

import com.roadhit.game.Heard
import com.roadhit.game.RecordedAudio
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream

/** What happens during one recognizer session: recognizer callbacks and the audio feed's state. */
internal sealed interface RecognizerEvent {
    /** [unstable] is the not-yet-stable tail (`android.speech.extra.UNSTABLE_TEXT`), if any. */
    data class Partial(val alternatives: List<String>, val unstable: String = "") : RecognizerEvent
    data class Results(val alternatives: List<String>) : RecognizerEvent
    data class Error(val code: Int) : RecognizerEvent
    object EndOfSpeech : RecognizerEvent

    /** The microphone stream ended (-1) on its own. */
    object InputEnded : RecognizerEvent

    /** The listening window is used up: `maxSeconds` of audio pumped, or of wall time in direct mode. */
    object InputFull : RecognizerEvent

    /** Time passing with no other event. */
    object Tick : RecognizerEvent
}

/** How a recognizer session ended. */
internal sealed interface SessionEnd {
    data class Results(val alternatives: List<String>, val fromPartial: Boolean) : SessionEnd
    data class Error(val code: Int) : SessionEnd
    object MicLost : SessionEnd {
        override fun toString() = "MicLost"
    }

    object Timeout : SessionEnd {
        override fun toString() = "Timeout"
    }

    object Unavailable : SessionEnd {
        override fun toString() = "Unavailable"
    }
}

internal sealed interface Step {
    object Continue : Step

    /** Stop feeding audio (close the pipe, or `stopListening()` in direct mode) so the recognizer finalises. */
    object StopInput : Step
    data class Finish(val end: SessionEnd) : Step
}

/**
 * Decides, event by event, when a session ends and how. Google's recognizer, fed through
 * EXTRA_AUDIO_SOURCE, delivers its final hypotheses in onPartialResults and then calls
 * onResults with an empty bundle, so an empty onResults falls back to the last non-empty partial.
 *
 * The same recognizer never signals end of speech for injected audio (it finalises only when
 * the pipe closes), so with [endpointMs] set the tracker decides it: once a partial has carried
 * text, [endpointMs] without any further text-carrying partial means the speaker has finished.
 */
internal class SessionTracker(private val endpointMs: Long? = null) {
    private var lastPartial: List<String> = emptyList()
    private var lastTextAt: Long? = null
    private var stopping = false
    private var finished = false

    fun on(event: RecognizerEvent, nowMs: Long = 0): Step {
        if (finished) return Step.Continue
        return when (event) {
            is RecognizerEvent.Partial -> {
                if (event.alternatives.any { it.isNotBlank() }) lastPartial = event.alternatives
                if (event.alternatives.any { it.isNotBlank() } || event.unstable.isNotBlank()) lastTextAt = nowMs
                Step.Continue
            }
            RecognizerEvent.Tick -> {
                val since = lastTextAt
                if (endpointMs != null && since != null && nowMs - since >= endpointMs) stop() else Step.Continue
            }
            is RecognizerEvent.Results -> finish(
                if (event.alternatives.isNotEmpty()) SessionEnd.Results(event.alternatives, fromPartial = false)
                else SessionEnd.Results(lastPartial, fromPartial = lastPartial.isNotEmpty()),
            )
            is RecognizerEvent.Error -> finish(SessionEnd.Error(event.code))
            RecognizerEvent.EndOfSpeech, RecognizerEvent.InputFull -> stop()
            RecognizerEvent.InputEnded -> if (stopping) Step.Continue else finish(SessionEnd.MicLost)
        }
    }

    private fun stop(): Step {
        if (stopping) return Step.Continue
        stopping = true
        return Step.StopInput
    }

    private fun finish(end: SessionEnd): Step {
        finished = true
        return Step.Finish(end)
    }
}

private const val ERROR_SPEECH_TIMEOUT = 6 // SpeechRecognizer.ERROR_SPEECH_TIMEOUT
private const val ERROR_NO_MATCH = 7 // SpeechRecognizer.ERROR_NO_MATCH

/**
 * The game's view of a session's end. Transcripts are passed on exactly as recognized. A no-match
 * with kept [audio] is [Heard.Unclear], not silence: the caller tries the other language.
 */
internal fun heardOf(end: SessionEnd, audio: RecordedAudio?): Heard = when (end) {
    is SessionEnd.Results ->
        if (end.alternatives.firstOrNull().isNullOrBlank()) Heard.Silence else Heard.Speech(end.alternatives, audio)
    is SessionEnd.Error -> when {
        // No match with audio kept: the other language may still read it (Conversation.listen).
        end.code == ERROR_NO_MATCH && audio != null && audio.pcm.isNotEmpty() -> Heard.Unclear(audio)
        end.code == ERROR_SPEECH_TIMEOUT || end.code == ERROR_NO_MATCH -> Heard.Silence
        else -> Heard.Failed("recognizer-${end.code}")
    }
    SessionEnd.MicLost -> Heard.Failed("mic-lost")
    SessionEnd.Timeout -> Heard.Failed("timeout")
    SessionEnd.Unavailable -> Heard.Failed("recognizer-unavailable")
}

/**
 * Whether a relisten should run once more: a recognizer error other than no-match / speech
 * timeout within [RELISTEN_RETRY_WINDOW_MS] of starting, on the first attempt only. Right after
 * the previous session, Google's service sometimes fails at once (error 11 or 2).
 */
internal fun shouldRetryRelisten(end: SessionEnd, elapsedMs: Long, attempt: Int): Boolean =
    attempt == 1 && elapsedMs < RELISTEN_RETRY_WINDOW_MS && end is SessionEnd.Error &&
        end.code != ERROR_SPEECH_TIMEOUT && end.code != ERROR_NO_MATCH

internal const val RELISTEN_RETRY_WINDOW_MS = 1_000L
internal const val RELISTEN_RETRY_DELAY_MS = 300L

/** The alternatives of a re-recognition; empty unless real speech was recognized. */
internal fun alternativesOf(end: SessionEnd): List<String> =
    (heardOf(end, null) as? Heard.Speech)?.transcripts ?: emptyList()

/** How often the event loop lets [SessionTracker] see time pass. */
internal const val TICK_MS = 100L

/**
 * Feeds [events] to [tracker] until it finishes; [SessionEnd.Timeout] after [timeoutMs].
 *
 * Ticks are sent into the same channel by a ticker coroutine and the loop uses a plain
 * `receive()`. A per-receive timeout (`withTimeoutOrNull(tick) { receive() }`) must not be used:
 * cancelling a receive after the element was handed over but before it resumed drops the
 * element, so a result arriving on a tick boundary was lost and the session timed out.
 */
internal suspend fun runEventLoop(
    events: Channel<RecognizerEvent>,
    tracker: SessionTracker,
    timeoutMs: Long,
    now: () -> Long,
    onStopInput: () -> Unit,
): SessionEnd = coroutineScope {
    val ticker = launch {
        while (true) {
            delay(TICK_MS)
            events.trySend(RecognizerEvent.Tick)
        }
    }
    try {
        withTimeoutOrNull(timeoutMs) { loop(events, tracker, now, onStopInput) } ?: SessionEnd.Timeout
    } finally {
        ticker.cancel()
    }
}

private suspend fun loop(
    events: ReceiveChannel<RecognizerEvent>,
    tracker: SessionTracker,
    now: () -> Long,
    onStopInput: () -> Unit,
): SessionEnd {
    var end: SessionEnd? = null
    while (end == null) {
        val event = events.receive()
        when (val step = tracker.on(event, now())) {
            Step.Continue -> {}
            Step.StopInput -> onStopInput()
            is Step.Finish -> end = step.end
        }
    }
    return end
}

/** Runs [block]; a failure other than cancellation is passed to [log] and gives [fallback]. */
internal suspend fun <T> guarded(fallback: T, log: (Exception) -> Unit, block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(e)
        fallback
    }

internal enum class PumpEnd { FULL, MIC_ENDED, STOPPED }

/**
 * Copies audio from [stream] to [out] (the recognizer's pipe), appending it to [copy] first,
 * until [maxBytes] have been sent, the stream ends or its read throws (unless [stop] is already
 * true), [stop] returns true, or [out] fails (the recognizer closed its end). Blocking; runs on
 * a background thread.
 */
internal fun pumpAudio(
    stream: MicStream,
    out: OutputStream,
    copy: OutputStream?,
    maxBytes: Int,
    stop: () -> Boolean,
    chunkBytes: Int = CHUNK_BYTES,
): PumpEnd {
    val buf = ByteArray(chunkBytes)
    var sent = 0
    while (!stop()) {
        val n = try {
            stream.read(buf)
        } catch (_: Exception) {
            // Closing the stream to end the session sets the stop flag first; any other failure
            // (e.g. the car host's IllegalStateException) means the microphone was lost.
            return if (stop()) PumpEnd.STOPPED else PumpEnd.MIC_ENDED
        }
        if (n < 0) return PumpEnd.MIC_ENDED
        val take = minOf(n, maxBytes - sent)
        copy?.write(buf, 0, take)
        try {
            out.write(buf, 0, take)
        } catch (_: IOException) {
            return PumpEnd.STOPPED
        }
        sent += take
        if (sent >= maxBytes) return PumpEnd.FULL
    }
    return PumpEnd.STOPPED
}

/** 20 ms of 16 kHz mono 16-bit audio. */
internal const val CHUNK_BYTES = 640
internal const val BYTES_PER_SECOND = 16_000 * 2
