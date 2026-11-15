package com.roadhit.car

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMicStreamTest {

    /** Like CarAudioRecord with a silent host: read blocks until stop() closes the stream. */
    private class BlockingRecording : CarMicStream.Recording {
        val readStarted = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val stops = AtomicInteger()
        val reads = AtomicInteger()

        override fun read(buf: ByteArray): Int {
            reads.incrementAndGet()
            readStarted.countDown()
            stopped.await()
            return -1
        }

        override fun stop() {
            stops.incrementAndGet()
            stopped.countDown()
        }
    }

    @Test
    fun closeFromAnotherThreadUnblocksPendingRead() {
        val recording = BlockingRecording()
        val stream = CarMicStream(recording)
        val result = AtomicInteger(Int.MIN_VALUE)
        val reader = thread { result.set(stream.read(ByteArray(512))) }
        assertTrue(recording.readStarted.await(1, TimeUnit.SECONDS))

        val closer = thread { stream.close() }
        closer.join(1_000)
        reader.join(1_000)

        assertTrue("close() must return promptly", !closer.isAlive)
        assertTrue("pending read() must return within a second of close()", !reader.isAlive)
        assertEquals(-1, result.get())
    }

    @Test
    fun readsAfterCloseReturnMinusOneAndSecondCloseIsNoOp() {
        val recording = BlockingRecording()
        val stream = CarMicStream(recording)

        stream.close()
        stream.close()

        assertEquals(1, recording.stops.get())
        assertEquals(-1, stream.read(ByteArray(512)))
        assertEquals("a closed stream must not touch the recording", 0, recording.reads.get())
    }

    @Test
    fun closeRacingAheadOfTheRecordReadGivesMinusOne() {
        // close() lands between read()'s closed check and the record read; CarAudioRecord
        // then throws IllegalStateException ("after calling stopRecording").
        lateinit var stream: CarMicStream
        stream = CarMicStream(object : CarMicStream.Recording {
            override fun read(buf: ByteArray): Int {
                stream.close()
                throw IllegalStateException("Called read after calling stopRecording")
            }

            override fun stop() {}
        })
        assertEquals(-1, stream.read(ByteArray(512)))
    }

    @Test(expected = IllegalStateException::class)
    fun recordErrorsWhileOpenStillPropagate() {
        CarMicStream(object : CarMicStream.Recording {
            override fun read(buf: ByteArray): Int = throw IllegalStateException("boom")
            override fun stop() {}
        }).read(ByteArray(512))
    }
}
