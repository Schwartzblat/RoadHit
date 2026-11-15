package com.roadhit.speech

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV files. The test-set (and microphone) format is 16 kHz mono. */
object Wav {
    /**
     * 16 kHz: the rate of the recorded test set and also of every live microphone source
     * ([PhoneMicSource] and the car's), not only of the tests; the name is historical.
     */
    const val TEST_SET_RATE = 16_000

    /** PCM samples (16-bit little-endian, interleaved if [channels] > 1) and their format. */
    class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int)

    fun write(file: File, pcm: ByteArray, sampleRate: Int = TEST_SET_RATE) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray()); putInt(pcm.size)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { it.write(header.array()); it.write(pcm) }
    }

    /** Reads a 16-bit PCM WAV, walking the chunks so extra chunks (LIST, fact) are skipped. */
    fun read(file: File): Pcm {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.remaining() >= 12 && tag(buf) == "RIFF") { "${file.name}: not a RIFF file" }
        buf.getInt()
        require(tag(buf) == "WAVE") { "${file.name}: not a WAVE file" }
        var sampleRate = 0
        var channels = 0
        while (buf.remaining() >= 8) {
            val id = tag(buf)
            val size = buf.getInt()
            val start = buf.position()
            when (id) {
                "fmt " -> {
                    val format = buf.getShort().toInt()
                    channels = buf.getShort().toInt()
                    sampleRate = buf.getInt()
                    buf.getInt(); buf.getShort()
                    val bits = buf.getShort().toInt()
                    require(format == 1 && bits == 16) { "${file.name}: need 16-bit PCM, got format $format, $bits bits" }
                }
                "data" -> {
                    require(sampleRate > 0) { "${file.name}: data before fmt" }
                    val length = minOf(size, buf.remaining()) // some writers leave the size at 0 or -1
                        .let { if (it <= 0) buf.remaining() else it }
                    val data = ByteArray(length).also { buf.get(it) }
                    return Pcm(data, sampleRate, channels)
                }
            }
            buf.position(minOf(buf.limit(), start + size + (size and 1)))
        }
        error("${file.name}: no data chunk")
    }

    /** Converts to 16 kHz mono: channels are averaged, the rate is changed by linear interpolation. */
    fun toTestSetFormat(pcm: Pcm): ByteArray {
        val src = ByteBuffer.wrap(pcm.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = src.remaining() / pcm.channels
        val mono = FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until pcm.channels) sum += src.get(f * pcm.channels + c)
            sum / pcm.channels
        }
        val outFrames = (frames.toLong() * TEST_SET_RATE / pcm.sampleRate).toInt()
        val out = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        val step = pcm.sampleRate.toDouble() / TEST_SET_RATE
        for (i in 0 until outFrames) {
            val pos = i * step
            val j = pos.toInt()
            val frac = (pos - j).toFloat()
            val a = mono[j]
            val b = if (j + 1 < frames) mono[j + 1] else a
            out.putShort((a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort())
        }
        return out.array()
    }

    private fun tag(buf: ByteBuffer): String = String(ByteArray(4).also { buf.get(it) }, Charsets.US_ASCII)
}
