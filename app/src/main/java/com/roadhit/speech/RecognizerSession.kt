package com.roadhit.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Audio fed to the recognizer through a pipe instead of its own microphone. */
internal class AudioFeed(
    val stream: MicStream,
    /** Stop after this many bytes ([BYTES_PER_SECOND] per second). */
    val maxBytes: Int,
    /** Receives a copy of every byte fed, before the recognizer gets it. Thread-safe. */
    val copy: OutputStream? = null,
)

/**
 * One recognizer session: creates a recognizer, feeds it [feed] through a pipe (or lets it use
 * the microphone when [feed] is null), and returns how the session ended. Bounded by
 * [timeoutMs] in all cases. In direct mode [directMaxMs] ends the listening window with
 * `stopListening()`. With [feed], [endSilenceMs] of quiet after the last recognized text ends
 * the input, since the recognizer does not endpoint injected audio.
 *
 * Every exit (result, error, timeout, cancellation) destroys the recognizer, closes the pipe
 * and closes [AudioFeed.stream]; the stream is closed here, the caller need not close it.
 * Must not run concurrently with itself.
 */
internal suspend fun runRecognizerSession(
    context: Context,
    langTag: String,
    feed: AudioFeed?,
    timeoutMs: Long,
    directMaxMs: Long? = null,
    onDevice: Boolean = false,
    endSilenceMs: Long = GoogleListener.DEFAULT_END_SILENCE_MS,
): SessionEnd = withContext(Dispatchers.Main) {
    val available = if (onDevice) SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    else SpeechRecognizer.isRecognitionAvailable(context)
    if (!available) {
        feed?.stream?.close()
        return@withContext SessionEnd.Unavailable
    }
    val events = Channel<RecognizerEvent>(Channel.UNLIMITED)
    val stopInput = AtomicBoolean(false)
    var recognizer: SpeechRecognizer? = null
    var readEnd: ParcelFileDescriptor? = null
    try {
        recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(EventAdapter(events))
        if (feed != null) {
            val pipe = ParcelFileDescriptor.createPipe()
            readEnd = pipe[0]
            // The pump owns the write end from here on, whatever happens next.
            startPump(feed, pipe[1], stopInput, events)
            recognizer.startListening(recognizerIntent(langTag, pipe[0]))
        } else {
            recognizer.startListening(recognizerIntent(langTag, null))
        }
        coroutineScope {
            val window = if (feed == null && directMaxMs != null) {
                launch { delay(directMaxMs); events.send(RecognizerEvent.InputFull) }
            } else null
            // The recognizer does not endpoint injected audio; in direct mode it does.
            val tracker = SessionTracker(endpointMs = if (feed != null) endSilenceMs else null)
            val end = runEventLoop(events, tracker, timeoutMs, now = { SystemClock.elapsedRealtime() }) {
                if (feed != null) stopInput.set(true) else recognizer.stopListening()
            }
            window?.cancel()
            Log.d(TAG, "recognizer $langTag ${if (feed != null) "injected" else "direct"} -> $end")
            end
        }
    } finally {
        // Order matters: destroying the recognizer and closing our read end make a blocked
        // pipe write fail; closing the stream unblocks a blocked read. The pump then closes
        // the write end itself.
        stopInput.set(true)
        recognizer?.cancel()
        recognizer?.destroy()
        readEnd?.close()
        feed?.stream?.close()
        events.close()
    }
}

/**
 * Pumps [feed] into the pipe's [writeEnd] on its own daemon thread, so a read or write stuck
 * in the kernel can never hold up the session; it ends once the session closes the stream
 * and the pipe's read side. Closing the write end tells the recognizer the audio is over.
 */
private fun startPump(
    feed: AudioFeed,
    writeEnd: ParcelFileDescriptor,
    stop: AtomicBoolean,
    events: Channel<RecognizerEvent>,
) {
    thread(name = "RoadHit-recognizer-feed", isDaemon = true) {
        val end = try {
            ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                pumpAudio(feed.stream, out, feed.copy, feed.maxBytes, stop = { stop.get() })
            }
        } catch (_: Exception) {
            // Not from the mic read or pipe write (pumpAudio handles those): closing the write
            // end failed, or something unexpected. Never let it escape this thread.
            if (stop.get()) PumpEnd.STOPPED else PumpEnd.MIC_ENDED
        }
        when (end) {
            PumpEnd.FULL -> events.trySend(RecognizerEvent.InputFull)
            PumpEnd.MIC_ENDED -> events.trySend(RecognizerEvent.InputEnded)
            PumpEnd.STOPPED -> {}
        }
    }
}

private class EventAdapter(private val events: Channel<RecognizerEvent>) : RecognitionListener {
    override fun onPartialResults(partialResults: Bundle) {
        val unstable = partialResults.getStringArrayList(UNSTABLE_TEXT)?.firstOrNull().orEmpty()
        events.trySend(RecognizerEvent.Partial(alternatives(partialResults), unstable))
    }

    override fun onResults(results: Bundle) {
        events.trySend(RecognizerEvent.Results(alternatives(results)))
    }

    override fun onError(error: Int) {
        events.trySend(RecognizerEvent.Error(error))
    }

    override fun onEndOfSpeech() {
        events.trySend(RecognizerEvent.EndOfSpeech)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun alternatives(results: Bundle): List<String> =
        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
}

private const val TAG = "RoadHit"


/** Google's recognizer puts the not-yet-stable tail of a partial hypothesis here. */
private const val UNSTABLE_TEXT = "android.speech.extra.UNSTABLE_TEXT"

private fun recognizerIntent(langTag: String, audio: ParcelFileDescriptor?) =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (audio != null) {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Wav.TEST_SET_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
    }
