package com.roadhit.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.roadhit.game.Heard
import com.roadhit.game.Lang
import com.roadhit.game.Listener
import com.roadhit.game.RecordedAudio
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [Listener] over Android's default speech recognizer (Google's; the on-device one has no Hebrew).
 *
 * Injected path (when [mic] returns a source): the answer is read from that microphone, fed to
 * the recognizer through a pipe and kept, so [relisten] can recognize it again in the other
 * language. [mic] is a function so the car's microphone can be handed over while a car session
 * is live. Direct path (when [mic] returns null, or [direct] is set): the recognizer opens the
 * microphone itself and no audio is kept.
 *
 * The listening window ends on end of speech or after `maxSeconds`; a listen never takes longer
 * than `maxSeconds` + [SLACK_MS]. One listen or relisten runs at a time.
 */
class GoogleListener(
    private val context: Context,
    private val mic: () -> MicSource?,
    private val direct: Boolean = false,
    /** Injected path: quiet after the last recognized text that ends the answer. */
    private val endSilenceMs: Long = DEFAULT_END_SILENCE_MS,
) : Listener {

    private val mutex = Mutex()

    @Volatile
    private var released = false

    override suspend fun listen(lang: Lang, maxSeconds: Int): Heard = mutex.withLock {
        if (released) return Heard.Failed("released")
        if (!hasPermission()) return Heard.Failed("no-permission")
        guarded(Heard.Failed("exception"), { Log.w(TAG, "listen failed", it) }) { listenOnce(lang, maxSeconds) }
    }

    private suspend fun listenOnce(lang: Lang, maxSeconds: Int): Heard {
        val timeoutMs = maxSeconds * 1000L + SLACK_MS
        val source = if (direct) null else mic()
        if (source == null) {
            val end = runRecognizerSession(context, lang.tag, feed = null, timeoutMs, directMaxMs = maxSeconds * 1000L)
            return heardOf(end, audio = null)
        }
        var opened: MicStream? = null
        val stream = try {
            withContext(Dispatchers.IO) { source.open().also { opened = it } } // CarMicSource.open blocks
        } catch (e: CancellationException) {
            opened?.close() // cancelled just as the microphone opened
            throw e
        } catch (_: Exception) {
            return Heard.Failed("mic-open")
        }
        try {
            val copy = ByteArrayOutputStream(maxSeconds * BYTES_PER_SECOND)
            val feed = AudioFeed(stream, maxBytes = maxSeconds * BYTES_PER_SECOND, copy = copy)
            val end = runRecognizerSession(context, lang.tag, feed, timeoutMs, endSilenceMs = endSilenceMs)
            return heardOf(end, RecordedAudio(copy.toByteArray()))
        } finally {
            stream.close() // the session closes it too; this covers cancellation before the session starts
        }
    }

    override suspend fun relisten(audio: RecordedAudio, lang: Lang): List<String> = mutex.withLock {
        if (released || !hasPermission() || audio.pcm.isEmpty()) return emptyList()
        val pcm = audio.pcm + ByteArray(TRAILING_SILENCE_BYTES) // lets the end-of-speech detector fire
        val timeoutMs = pcm.size * 1000L / BYTES_PER_SECOND + SLACK_MS // the duration bounds the paced feed too
        guarded(emptyList(), { Log.w(TAG, "relisten failed", it) }) { relistenOnce(pcm, lang, timeoutMs) }
    }

    /** One recognition of [pcm], run a second time after an immediate recognizer error. */
    private suspend fun relistenOnce(pcm: ByteArray, lang: Lang, timeoutMs: Long): List<String> {
        var attempt = 1
        while (true) {
            val start = SystemClock.elapsedRealtime()
            val feed = AudioFeed(BytesMicStream(pcm, RELISTEN_SPEEDUP), pcm.size)
            val end = runRecognizerSession(context, lang.tag, feed, timeoutMs, endSilenceMs = endSilenceMs)
            if (!shouldRetryRelisten(end, SystemClock.elapsedRealtime() - start, attempt)) return alternativesOf(end)
            Log.i(TAG, "relisten retry after recognizer-${(end as SessionEnd.Error).code}")
            delay(RELISTEN_RETRY_DELAY_MS)
            attempt++
        }
    }

    /** Later calls to [listen] fail and [relisten] returns nothing; a session in progress finishes normally. */
    fun release() {
        released = true
    }

    private fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** How long past its audio a session may take before it is abandoned. */
        const val SLACK_MS = 8_000L
        private const val TAG = "RoadHit"

        /**
         * Quiet after the last recognized text that ends an answer on the injected path (the
         * recognizer gives no end of speech there). Long enough for a hesitation before the
         * year; see the plan's Decision Log.
         */
        const val DEFAULT_END_SILENCE_MS = 2_000L
        private const val TRAILING_SILENCE_BYTES = 2 * BYTES_PER_SECOND

        /**
         * Kept audio is fed this many times faster than real time. Unpaced, Google's service
         * sometimes fails with MICROPHONE_AUDIO_BUFFER_OVERFLOW (error 2 or 11), seen when the
         * session starts right after the previous one.
         */
        private const val RELISTEN_SPEEDUP = 4
    }
}

internal val Lang.tag: String
    get() = when (this) {
        Lang.HE -> "he-IL"
        Lang.EN -> "en-US"
    }

/**
 * A [MicStream] over bytes already in memory, then -1. With [speedup] > 0 it is paced at that
 * multiple of real time (16 kHz mono 16-bit); with 0 it reads as fast as asked.
 */
internal class BytesMicStream(private val bytes: ByteArray, private val speedup: Int = 0) : MicStream {
    @Volatile
    private var pos = 0

    @Volatile
    private var closed = false

    private var startNs = 0L

    override fun read(buf: ByteArray): Int {
        if (startNs == 0L) startNs = System.nanoTime()
        if (speedup > 0) {
            val dueNs = startNs + pos * 1_000_000_000L / BYTES_PER_SECOND / speedup
            while (!closed && System.nanoTime() < dueNs) Thread.sleep(1)
        }
        if (closed || pos >= bytes.size) return -1
        val n = minOf(buf.size, bytes.size - pos)
        bytes.copyInto(buf, 0, pos, pos + n)
        pos += n
        return n
    }

    override fun close() {
        closed = true
    }
}
