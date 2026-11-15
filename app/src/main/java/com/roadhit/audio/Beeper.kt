package com.roadhit.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The short "your answer now" tone: a generated sine on an [AudioTrack] with media usage,
 * so in the car it comes out of the media channel like everything else.
 */
class Beeper {

    private val tone: ShortArray by lazy { sineTone() }

    /** Plays the tone; returns when it has finished. Cancelling stops it at once. */
    suspend fun beep(): Unit = withContext(Dispatchers.Default) {
        val samples = tone
        val track = try {
            newTrack(samples.size * 2)
        } catch (e: Exception) {
            // UnsupportedOperationException / IllegalArgumentException: no output available.
            Log.w(TAG, "beep failed ${e.javaClass.simpleName}")
            return@withContext
        }
        try {
            track.write(samples, 0, samples.size)
            track.play()
            delay(DURATION_MS.toLong())
            // The head position lags a little behind the wall clock; wait for the last frame.
            var waited = 0L
            while (track.playbackHeadPosition < samples.size && waited < MAX_EXTRA_WAIT_MS) {
                delay(POLL_MS)
                waited += POLL_MS
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    private fun newTrack(bytes: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(bytes)
        .build()

    private fun sineTone(): ShortArray {
        val n = SAMPLE_RATE * DURATION_MS / 1000
        val fade = SAMPLE_RATE * FADE_MS / 1000
        return ShortArray(n) { i ->
            // Short fades in and out avoid clicks.
            val envelope = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (sin(2 * PI * FREQUENCY_HZ * i / SAMPLE_RATE) * envelope * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private companion object {
        const val TAG = "RoadHit"
        const val SAMPLE_RATE = 48_000
        const val DURATION_MS = 200
        const val FADE_MS = 10
        const val FREQUENCY_HZ = 880.0
        const val AMPLITUDE = 0.5
        const val POLL_MS = 10L
        const val MAX_EXTRA_WAIT_MS = 300L
    }
}
