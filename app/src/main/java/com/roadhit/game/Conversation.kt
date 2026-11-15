package com.roadhit.game

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

private const val FAILURES_BEFORE_OFFLINE = 3

/**
 * The engine's side of the conversation: speaking, listening, the screen and the media
 * buttons. Buttons arrive on any thread into a channel and are taken, in order, only by the
 * coroutine running the game, while it waits on a port call ([whileWatching]) or in [waitForPlay].
 * Everything except [press] must be called from that one coroutine.
 */
internal class Conversation(
    private val speaker: Speaker,
    private val listener: Listener,
    private val display: Display,
    private val prefs: () -> Prefs,
) {
    private val buttons = Channel<Button>(Channel.UNLIMITED)

    /** A PAUSE was pressed and has not taken effect yet. */
    var pausePending = false
        private set

    /**
     * A PLAY arrived while a PAUSE had not taken effect yet (e.g. a short audio-focus loss inside
     * one utterance or listen): the pause still restarts the turn, but [waitForPlay] does not wait.
     */
    private var resumeQueued = false

    /** Consecutive [Heard.Failed] results, across setup and turns. */
    private var failuresInRow = 0

    private var lastView = idleView()

    /** Safe from any thread; never blocks. */
    fun press(b: Button) {
        buttons.trySend(b)
    }

    /** Forgets buttons and state left from an earlier run. */
    fun reset() {
        while (buttons.tryReceive().isSuccess) { /* drop */ }
        pausePending = false
        resumeQueued = false
        failuresInRow = 0
    }

    val phrases: Phrases get() = phrasesFor(prefs().lang)

    fun show(view: GameView) {
        lastView = view
        display.show(view)
    }

    suspend fun say(text: (Phrases) -> String) {
        val p = phrases
        sayParts(listOf(Utterance(text(p), p.lang)))
    }

    suspend fun sayParts(parts: List<Utterance>) = whileWatching({ speaker.say(parts) })

    suspend fun beep() = whileWatching({ speaker.beep() })

    /**
     * Listens in the app language; buttons other than PAUSE go to [onButton]. An answer the app
     * language cannot read ([Heard.Unclear]) is recognized once more in the other language, inside
     * the same watch: it becomes [Heard.Speech] with those transcripts and no audio (both passes
     * are done), or [Heard.Silence] when that pass finds nothing. Never returns [Heard.Unclear].
     */
    suspend fun listen(maxSeconds: Int, onButton: (Button) -> Unit = {}): Heard =
        whileWatching({
            val lang = prefs().lang
            when (val heard = listener.listen(lang, maxSeconds)) {
                is Heard.Unclear -> {
                    val other = if (lang == Lang.HE) Lang.EN else Lang.HE
                    val again = listener.relisten(heard.audio, other)
                    if (again.firstOrNull().isNullOrBlank()) Heard.Silence else Heard.Speech(again, audio = null)
                }
                else -> heard
            }
        }, onButton)

    /**
     * Runs [work] while taking buttons in order: PAUSE is remembered in [pausePending], a PLAY
     * after it in [resumeQueued] (a later PAUSE cancels that again), and every button is also
     * handed to [onButton]; buttons it ignores are dropped.
     */
    suspend fun <T> whileWatching(work: suspend () -> T, onButton: (Button) -> Unit = {}): T = coroutineScope {
        val job = async { work() }
        while (
            select {
                job.onAwait { false }
                buttons.onReceive { b ->
                    when (b) {
                        Button.PAUSE -> {
                            pausePending = true
                            resumeQueued = false
                        }
                        Button.PLAY -> if (pausePending) resumeQueued = true
                        else -> Unit
                    }
                    onButton(b)
                    true
                }
            }
        ) {
            // keep taking buttons until the work is done
        }
        job.await()
    }

    /**
     * Shows [Phase.PAUSED] and waits for PLAY; every other button is dropped. Returns at once when
     * a PLAY already followed the pending PAUSE ([resumeQueued]).
     */
    suspend fun waitForPlay() {
        if (!resumeQueued) {
            show(lastView.copy(phase = Phase.PAUSED, revealed = null))
            while (buttons.receive() != Button.PLAY) { /* drop */ }
        }
        resumeQueued = false
        pausePending = false
    }

    /**
     * Says noConnection before a connection pause; that pause waits for its own PLAY even if a
     * PAUSE and PLAY arrived meanwhile (they do not resume it).
     */
    suspend fun sayNoConnection() {
        say { it.noConnection() }
        resumeQueued = false
    }

    /** A listen worked (speech or silence): the failure streak ends. */
    fun heardSomething() {
        failuresInRow = 0
    }

    /**
     * After a [Heard.Failed]: says didNotUnderstand, or on the third failure in a row says
     * noConnection and returns true (the caller then pauses).
     */
    suspend fun listenFailed(): Boolean {
        if (++failuresInRow >= FAILURES_BEFORE_OFFLINE) {
            failuresInRow = 0
            sayNoConnection()
            return true
        }
        say { it.didNotUnderstand() }
        return false
    }
}

internal fun idleView(phase: Phase = Phase.IDLE) = GameView(phase, emptyList(), emptyList(), 0, 0, 0, null)
