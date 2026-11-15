package com.roadhit.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** A microphone that delivers 16 kHz, mono, 16-bit little-endian PCM. */
interface MicSource {
    fun open(): MicStream
}

/** An open microphone. [read] blocks until audio is available and returns -1 once closed. */
interface MicStream : Closeable {
    fun read(buf: ByteArray): Int
}

/** The phone's own microphone, tuned for speech ([MediaRecorder.AudioSource.VOICE_RECOGNITION]). */
class PhoneMicSource(private val context: Context) : MicSource {

    override fun open(): MicStream {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO not granted")
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            maxOf(minBuffer, SAMPLE_RATE), // bytes: at least half a second
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        record.startRecording()
        return AudioRecordStream(record)
    }

    private class AudioRecordStream(private val record: AudioRecord) : MicStream {
        private val lock = Any()

        private val closed = AtomicBoolean(false)

        override fun read(buf: ByteArray): Int = synchronized(lock) {
            if (closed.get()) return -1
            val n = record.read(buf, 0, buf.size)
            if (n < 0 || closed.get()) -1 else n
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            record.stop() // unblocks a pending read
            synchronized(lock) { record.release() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
