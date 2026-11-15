package com.roadhit.car

import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import com.roadhit.speech.MicSource
import com.roadhit.speech.MicStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The car's microphone through the Android Auto host ([CarAudioRecord]): 16 kHz, mono,
 * 16-bit PCM, the same format as [com.roadhit.speech.PhoneMicSource].
 *
 * The caller must hold RECORD_AUDIO. No separate audio focus is requested for the stream: the
 * game service holds `AUDIOFOCUS_GAIN` (media) for the whole game. [open] makes a blocking call
 * to the host, so call it off the main thread.
 */
class CarMicSource(private val carContext: CarContext) : MicSource {

    override fun open(): MicStream {
        if (carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO not granted")
        }
        val record = CarAudioRecord.create(carContext)
        record.startRecording()
        return CarMicStream(object : CarMicStream.Recording {
            override fun read(buf: ByteArray): Int = record.read(buf, 0, buf.size)
            override fun stop() = record.stopRecording()
        })
    }
}

/**
 * An open car microphone. [close] may be called from any thread: it unblocks a pending
 * [read], which then returns -1, as does every later [read].
 */
internal class CarMicStream(private val recording: Recording) : MicStream {

    /** The started recording: [CarAudioRecord] in the app, a fake in tests. */
    internal interface Recording {
        /** Blocks until audio is available; -1 once the microphone is closed or stopped. */
        fun read(buf: ByteArray): Int

        /** Stops the recording; unblocks a pending [read]. */
        fun stop()
    }

    private val closed = AtomicBoolean(false)

    override fun read(buf: ByteArray): Int {
        if (closed.get()) return -1
        val n = try {
            recording.read(buf)
        } catch (e: IllegalStateException) {
            // CarAudioRecord throws if close() stopped it between the check above and this read.
            if (closed.get()) return -1 else throw e
        }
        return if (n < 0 || closed.get()) -1 else n
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // No lock around stop(): CarAudioRecord releases its own lock before the blocking
        // stream read, and stopping closes that stream, so a pending read returns -1.
        recording.stop()
    }
}
