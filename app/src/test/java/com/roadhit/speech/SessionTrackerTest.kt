package com.roadhit.speech

import com.roadhit.game.Heard
import com.roadhit.game.RecordedAudio
import com.roadhit.speech.RecognizerEvent.EndOfSpeech
import com.roadhit.speech.RecognizerEvent.Error
import com.roadhit.speech.RecognizerEvent.InputEnded
import com.roadhit.speech.RecognizerEvent.InputFull
import com.roadhit.speech.RecognizerEvent.Partial
import com.roadhit.speech.RecognizerEvent.Results
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrackerTest {

    private fun finish(vararg events: RecognizerEvent): SessionEnd {
        val tracker = SessionTracker()
        for (e in events) {
            val step = tracker.on(e)
            if (step is Step.Finish) return step.end
        }
        error("no Finish after $events")
    }

    @Test
    fun `results are used as given`() {
        assertEquals(
            SessionEnd.Results(listOf("a", "b"), fromPartial = false),
            finish(Partial(listOf("x")), Results(listOf("a", "b"))),
        )
    }

    @Test
    fun `empty onResults falls back to the last non-empty partial`() {
        assertEquals(
            SessionEnd.Results(listOf("שוב של", "שוב"), fromPartial = true),
            finish(Partial(listOf("שוב")), Partial(listOf("שוב של", "שוב")), Partial(emptyList()), Results(emptyList())),
        )
    }

    @Test
    fun `empty onResults with no partial gives empty results`() {
        assertEquals(SessionEnd.Results(emptyList(), fromPartial = false), finish(Results(emptyList())))
    }

    @Test
    fun `partials alone do not finish`() {
        val tracker = SessionTracker()
        assertSame(Step.Continue, tracker.on(Partial(listOf("a"))))
    }

    @Test
    fun `recognizer error finishes with its code`() {
        assertEquals(SessionEnd.Error(2), finish(Partial(listOf("a")), Error(2)))
    }

    @Test
    fun `end of speech and full input stop the input but keep waiting`() {
        val tracker = SessionTracker()
        assertSame(Step.StopInput, tracker.on(EndOfSpeech))
        assertSame(Step.Continue, tracker.on(InputFull))
        assertSame(Step.Continue, tracker.on(InputEnded)) // the mic closing after we stopped is not a loss
        assertEquals(Step.Finish(SessionEnd.Results(listOf("a"), false)), tracker.on(Results(listOf("a"))))
    }

    @Test
    fun `full input stops the input`() {
        assertSame(Step.StopInput, SessionTracker().on(InputFull))
    }

    @Test
    fun `mic ending before any result and before the limit is a lost mic`() {
        assertEquals(SessionEnd.MicLost, finish(Partial(listOf("a")), InputEnded))
    }

    @Test
    fun `events after finish are ignored`() {
        val tracker = SessionTracker()
        tracker.on(Error(7))
        assertSame(Step.Continue, tracker.on(Results(listOf("late"))))
    }

    // --- Our own end of speech: with injected audio the recognizer never signals it

    @Test
    fun `quiet after heard speech stops the input`() {
        val tracker = SessionTracker(endpointMs = 1500)
        assertSame(Step.Continue, tracker.on(Partial(emptyList(), unstable = "כל"), nowMs = 1000))
        assertSame(Step.Continue, tracker.on(RecognizerEvent.Tick, nowMs = 2400))
        assertSame(Step.Continue, tracker.on(Partial(listOf("כלניות")), nowMs = 2400))
        assertSame(Step.Continue, tracker.on(RecognizerEvent.Tick, nowMs = 3899))
        assertSame(Step.StopInput, tracker.on(RecognizerEvent.Tick, nowMs = 3900))
        assertSame(Step.Continue, tracker.on(RecognizerEvent.Tick, nowMs = 5000)) // only once
        assertEquals(Step.Finish(SessionEnd.Results(listOf("כלניות"), true)), tracker.on(Results(emptyList()), 5100))
    }

    @Test
    fun `default end-of-speech pause is two seconds`() {
        assertEquals(2000L, GoogleListener.DEFAULT_END_SILENCE_MS)
        val tracker = SessionTracker(endpointMs = GoogleListener.DEFAULT_END_SILENCE_MS)
        tracker.on(Partial(listOf("shape of you של אד שירן")), nowMs = 10_000)
        assertSame(Step.Continue, tracker.on(RecognizerEvent.Tick, nowMs = 11_999)) // hesitating before the year
        assertSame(Step.StopInput, tracker.on(RecognizerEvent.Tick, nowMs = 12_000))
    }

    @Test
    fun `quiet before any speech does not stop the input`() {
        val tracker = SessionTracker(endpointMs = 1500)
        assertSame(Step.Continue, tracker.on(Partial(emptyList()), nowMs = 0))
        assertSame(Step.Continue, tracker.on(RecognizerEvent.Tick, nowMs = 10_000))
    }

    @Test
    fun `without an endpoint quiet never stops the input`() {
        val tracker = SessionTracker()
        tracker.on(Partial(listOf("a")), nowMs = 0)
        assertSame(Step.Continue, tracker.on(RecognizerEvent.Tick, nowMs = 60_000))
    }

    @Test
    fun `mic ending after our end of speech is not a loss`() {
        val tracker = SessionTracker(endpointMs = 1000)
        tracker.on(Partial(listOf("a")), nowMs = 0)
        assertSame(Step.StopInput, tracker.on(RecognizerEvent.Tick, nowMs = 1000))
        assertSame(Step.Continue, tracker.on(InputEnded, nowMs = 1100))
    }

    // --- Heard mapping

    private val audio = RecordedAudio(ByteArray(4))

    @Test
    fun `non-blank top alternative is speech with the audio`() {
        val heard = heardOf(SessionEnd.Results(listOf("שוב 1976", " "), true), audio)
        assertTrue(heard is Heard.Speech)
        heard as Heard.Speech
        assertEquals(listOf("שוב 1976", " "), heard.transcripts)
        assertSame(audio, heard.audio)
    }

    @Test
    fun `transcripts are not normalised`() {
        val heard = heardOf(SessionEnd.Results(listOf("‏shape of you "), false), null) as Heard.Speech
        assertEquals(listOf("‏shape of you "), heard.transcripts)
        assertEquals(null, heard.audio)
    }

    @Test
    fun `blank or missing top alternative is silence`() {
        assertSame(Heard.Silence, heardOf(SessionEnd.Results(emptyList(), false), audio))
        assertSame(Heard.Silence, heardOf(SessionEnd.Results(listOf("  ", "a"), false), audio))
    }

    @Test
    fun `no match with kept audio is unclear and carries the audio`() {
        val heard = heardOf(SessionEnd.Error(7), audio)
        assertTrue(heard is Heard.Unclear)
        assertSame(audio, (heard as Heard.Unclear).audio)
    }

    @Test
    fun `no match without audio and speech timeout are silence`() {
        assertSame(Heard.Silence, heardOf(SessionEnd.Error(7), null))
        assertSame(Heard.Silence, heardOf(SessionEnd.Error(7), RecordedAudio(ByteArray(0))))
        assertSame(Heard.Silence, heardOf(SessionEnd.Error(6), audio))
        assertSame(Heard.Silence, heardOf(SessionEnd.Error(6), null))
    }

    @Test
    fun `other errors fail with their reason`() {
        assertEquals(Heard.Failed("recognizer-2"), heardOf(SessionEnd.Error(2), audio))
        assertEquals(Heard.Failed("recognizer-11"), heardOf(SessionEnd.Error(11), audio))
        assertEquals(Heard.Failed("mic-lost"), heardOf(SessionEnd.MicLost, audio))
        assertEquals(Heard.Failed("timeout"), heardOf(SessionEnd.Timeout, audio))
        assertEquals(Heard.Failed("recognizer-unavailable"), heardOf(SessionEnd.Unavailable, audio))
    }

    @Test
    fun `relisten keeps only real speech`() {
        assertEquals(listOf("a", "b"), alternativesOf(SessionEnd.Results(listOf("a", "b"), false)))
        assertEquals(emptyList<String>(), alternativesOf(SessionEnd.Results(listOf(""), false)))
        assertEquals(emptyList<String>(), alternativesOf(SessionEnd.Error(7)))
        assertEquals(emptyList<String>(), alternativesOf(SessionEnd.Error(2)))
        assertEquals(emptyList<String>(), alternativesOf(SessionEnd.Timeout))
        assertEquals(emptyList<String>(), alternativesOf(SessionEnd.MicLost))
    }

    // --- Pump (the background loop that feeds the recognizer)

    private class ChunkStream(private val chunks: ArrayDeque<ByteArray>, private val endWith: Int = -1) : MicStream {
        var closed = false
        override fun read(buf: ByteArray): Int {
            val c = chunks.removeFirstOrNull() ?: return endWith
            c.copyInto(buf)
            return c.size
        }
        override fun close() { closed = true }
    }

    @Test
    fun `pump stops at the byte limit and keeps a copy of what it sent`() {
        val stream = ChunkStream(ArrayDeque(List(5) { i -> ByteArray(4) { i.toByte() } }))
        val out = java.io.ByteArrayOutputStream()
        val copy = java.io.ByteArrayOutputStream()
        val end = pumpAudio(stream, out, copy, maxBytes = 10, stop = { false }, chunkBytes = 4)
        assertEquals(PumpEnd.FULL, end)
        assertEquals(listOf<Byte>(0, 0, 0, 0, 1, 1, 1, 1, 2, 2), out.toByteArray().toList())
        assertEquals(out.toByteArray().toList(), copy.toByteArray().toList())
    }

    @Test
    fun `pump reports a stream that ends early`() {
        val stream = ChunkStream(ArrayDeque(listOf(ByteArray(4))))
        val out = java.io.ByteArrayOutputStream()
        assertEquals(PumpEnd.MIC_ENDED, pumpAudio(stream, out, null, maxBytes = 100, stop = { false }, chunkBytes = 4))
        assertEquals(4, out.size())
    }

    @Test
    fun `pump stops when asked`() {
        val stream = ChunkStream(ArrayDeque(List(5) { ByteArray(4) }))
        var reads = 0
        val end = pumpAudio(stream, java.io.ByteArrayOutputStream(), null, 100, stop = { reads++ >= 2 }, chunkBytes = 4)
        assertEquals(PumpEnd.STOPPED, end)
    }

    @Test
    fun `pump treats a closed pipe as stopped`() {
        val stream = ChunkStream(ArrayDeque(List(5) { ByteArray(4) }))
        val broken = object : java.io.OutputStream() {
            override fun write(b: Int) = throw java.io.IOException("EPIPE")
        }
        assertEquals(PumpEnd.STOPPED, pumpAudio(stream, broken, null, 100, stop = { false }, chunkBytes = 4))
    }

    // --- Bytes as a microphone (relisten)

    @Test
    fun `bytes stream reads everything then ends`() {
        val stream = BytesMicStream(ByteArray(1000) { it.toByte() })
        val buf = ByteArray(640)
        assertEquals(640, stream.read(buf))
        assertEquals(360, stream.read(buf))
        assertEquals(-1, stream.read(buf))
    }

    @Test
    fun `paced bytes stream runs at the given multiple of real time`() {
        val stream = BytesMicStream(ByteArray(BYTES_PER_SECOND), speedup = 4) // one second of audio
        val buf = ByteArray(CHUNK_BYTES)
        val start = System.nanoTime()
        var chunks = 0
        while (stream.read(buf) >= 0) chunks++
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms in 230..600)
        assertEquals(BYTES_PER_SECOND / CHUNK_BYTES, chunks)
    }

    @Test
    fun `closed bytes stream ends`() {
        val stream = BytesMicStream(ByteArray(100))
        stream.close()
        assertEquals(-1, stream.read(ByteArray(10)))
    }

    // --- Review fixes

    @Test
    fun `a blank partial does not replace good text`() {
        assertEquals(
            SessionEnd.Results(listOf("shape of you"), fromPartial = true),
            finish(Partial(listOf("shape of you")), Partial(listOf("")), Results(emptyList())),
        )
        val heard = heardOf(SessionEnd.Results(listOf("shape of you"), true), null) as Heard.Speech
        assertEquals(listOf("shape of you"), heard.transcripts)
    }

    private class ThrowingStream(private val goodReads: Int) : MicStream {
        var reads = 0
        override fun read(buf: ByteArray): Int {
            if (reads++ < goodReads) return buf.size
            throw IllegalStateException("car host took the microphone")
        }
        override fun close() {}
    }

    @Test
    fun `a mic read that throws is the input ending`() {
        val end = pumpAudio(ThrowingStream(2), java.io.ByteArrayOutputStream(), null, 10_000, stop = { false }, chunkBytes = 4)
        assertEquals(PumpEnd.MIC_ENDED, end)
    }

    @Test
    fun `a mic read that throws because we stopped is a normal stop`() {
        var stopped = false
        val stream = object : MicStream {
            override fun read(buf: ByteArray): Int {
                stopped = true // the session closed the stream (after setting the stop flag) during the read
                throw IllegalStateException("closed")
            }
            override fun close() {}
        }
        assertEquals(PumpEnd.STOPPED, pumpAudio(stream, java.io.ByteArrayOutputStream(), null, 100, stop = { stopped }, chunkBytes = 4))
    }

    @Test
    fun `guarded returns the fallback and logs a failure`() = kotlinx.coroutines.test.runTest {
        val logged = mutableListOf<Exception>()
        val result = guarded(Heard.Failed("exception"), { logged += it }) { throw java.io.IOException("no fds") }
        assertEquals(Heard.Failed("exception"), result)
        assertEquals("no fds", logged.single().message)
        assertEquals(listOf("a"), guarded(emptyList<String>(), { logged += it }) { listOf("a") })
    }

    @Test
    fun `guarded lets cancellation through`() = kotlinx.coroutines.test.runTest {
        val logged = mutableListOf<Exception>()
        try {
            guarded(emptyList<String>(), { logged += it }) { throw kotlinx.coroutines.CancellationException("cancelled") }
            org.junit.Assert.fail("cancellation was swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        assertTrue(logged.isEmpty())
    }

    /**
     * Terminal events sent exactly on tick boundaries (virtual time) must never be lost: the
     * old `withTimeoutOrNull(tick) { receive() }` could drop an element handed over as the
     * tick timeout fired, turning a result into a timeout.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // testScheduler.currentTime
    @Test
    fun `event loop never loses an event sent on a tick boundary`() {
        kotlinx.coroutines.test.runTest {
            for (offset in -1L..1L) for (round in 1..50) {
                val events = kotlinx.coroutines.channels.Channel<RecognizerEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val sender = launch {
                    kotlinx.coroutines.delay(round * 7L)
                    events.send(Partial(listOf("a"))) // the loop re-arms its tick here...
                    kotlinx.coroutines.delay(TICK_MS + offset) // ...so this lands on the tick boundary
                    events.send(Results(listOf("hit")))
                }
                val end = runEventLoop(events, SessionTracker(endpointMs = 1_000_000), timeoutMs = 60_000, now = { testScheduler.currentTime }) {}
                sender.join()
                assertEquals("offset $offset round $round", SessionEnd.Results(listOf("hit"), false), end)
            }
        }
    }

    // --- Relisten retry after an immediate recognizer error

    @Test
    fun `relisten retries once after an immediate recognizer error`() {
        assertTrue(shouldRetryRelisten(SessionEnd.Error(11), elapsedMs = 25, attempt = 1))
        assertEquals(false, shouldRetryRelisten(SessionEnd.Error(11), elapsedMs = 25, attempt = 2))
        assertEquals(false, shouldRetryRelisten(SessionEnd.Error(7), elapsedMs = 25, attempt = 1))
        assertEquals(false, shouldRetryRelisten(SessionEnd.Error(6), elapsedMs = 25, attempt = 1))
        assertEquals(false, shouldRetryRelisten(SessionEnd.Error(2), elapsedMs = 1500, attempt = 1))
        assertEquals(false, shouldRetryRelisten(SessionEnd.Results(listOf("a"), false), elapsedMs = 25, attempt = 1))
        assertEquals(false, shouldRetryRelisten(SessionEnd.Results(emptyList(), false), elapsedMs = 25, attempt = 1))
        assertEquals(false, shouldRetryRelisten(SessionEnd.Timeout, elapsedMs = 25, attempt = 1))
    }
}
