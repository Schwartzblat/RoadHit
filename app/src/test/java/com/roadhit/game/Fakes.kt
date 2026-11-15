package com.roadhit.game

import com.roadhit.catalog.Catalog
import kotlinx.coroutines.CompletableDeferred

/** One ordered record of everything the engine did through the fakes. */
class EventLog {
    val events = mutableListOf<String>()
    operator fun plusAssign(e: String) {
        events += e
    }

    /** The event right after the first one matching [prefix] at or after [from]. */
    fun after(prefix: String, from: Int = 0): String? {
        val i = events.withIndex().indexOfFirst { (j, e) -> j >= from && e.startsWith(prefix) }
        return if (i < 0) null else events.getOrNull(i + 1)
    }
}

class FakeClipPlayer(private val log: EventLog) : ClipPlayer {
    val played = mutableListOf<String>()
    var stops = 0

    /** How many of the next play() calls fail to load. */
    var failNext = 0

    /** When true, play() stays suspended until stop() or release(). */
    var hold = false
    private var current: CompletableDeferred<Boolean>? = null
    val isPlaying get() = current != null

    override suspend fun play(song: Song, maxSeconds: Int): Boolean {
        played += song.id
        log += "play:${song.id}"
        if (failNext > 0) {
            failNext--
            return false
        }
        if (!hold) return true
        val d = CompletableDeferred<Boolean>()
        current = d
        try {
            return d.await()
        } finally {
            current = null
        }
    }

    override fun stop() {
        stops++
        log += "stop"
        current?.complete(true)
    }

    /** Lets a held clip end on its own. */
    fun release() {
        current?.complete(true)
    }
}

class FakeSpeaker(private val log: EventLog) : Speaker {
    val said = mutableListOf<List<Utterance>>()
    val texts get() = said.flatten().map { it.text }
    var beeps = 0

    /** When a say() contains this text, it stays suspended until release(). */
    var holdOn: String? = null
    private var gate: CompletableDeferred<Unit>? = null
    val isHolding get() = gate != null

    override suspend fun say(parts: List<Utterance>) {
        said += parts
        log += "say:" + parts.joinToString(" | ") { it.text }
        if (holdOn != null && parts.any { it.text == holdOn }) {
            holdOn = null
            val g = CompletableDeferred<Unit>()
            gate = g
            try {
                g.await()
            } finally {
                gate = null
            }
        }
    }

    override suspend fun beep() {
        beeps++
        log += "beep"
    }

    fun release() {
        gate?.complete(Unit)
    }

    fun count(text: String) = texts.count { it == text }
}

class FakeListener(private val log: EventLog) : Listener {
    private val script = ArrayDeque<suspend () -> Heard>()
    val listens = mutableListOf<Pair<Lang, Int>>()
    val relistens = mutableListOf<Lang>()

    /** What relisten() returns, in order; an empty list once used up. */
    val relistenResults = ArrayDeque<List<String>>()

    /** When set, the next relisten() stays suspended until this completes (then it is cleared). */
    var relistenGate: CompletableDeferred<Unit>? = null
    val isRelistening get() = relistenGate != null && relistenStarted
    private var relistenStarted = false

    fun speech(vararg transcripts: String, audio: Boolean = false) {
        script.add { Heard.Speech(transcripts.toList(), if (audio) RecordedAudio(ByteArray(4)) else null) }
    }

    /** A no-match first pass with kept audio (the other language has not been tried yet). */
    fun unclear(times: Int = 1) = repeat(times) { script.add { Heard.Unclear(RecordedAudio(ByteArray(4))) } }

    fun silence(times: Int = 1) = repeat(times) { script.add { Heard.Silence } }
    fun failed(times: Int = 1) = repeat(times) { script.add { Heard.Failed("offline") } }

    /** The listen at this point of the script stays suspended until the returned deferred completes. */
    fun hold(): CompletableDeferred<Heard> {
        val d = CompletableDeferred<Heard>()
        script.add { d.await() }
        return d
    }

    val remaining get() = script.size

    override suspend fun listen(lang: Lang, maxSeconds: Int): Heard {
        listens += lang to maxSeconds
        log += "listen"
        val next = script.removeFirstOrNull() ?: throw AssertionError("listener script exhausted")
        return next()
    }

    override suspend fun relisten(audio: RecordedAudio, lang: Lang): List<String> {
        relistens += lang
        log += "relisten:$lang"
        relistenGate?.let { gate ->
            relistenStarted = true
            try {
                gate.await()
            } finally {
                relistenGate = null
                relistenStarted = false
            }
        }
        return relistenResults.removeFirstOrNull() ?: emptyList()
    }
}

class FakeDisplay : Display {
    val views = mutableListOf<GameView>()
    val last get() = views.last()
    override fun show(view: GameView) {
        views += view
    }
}

class FakeStore(
    private val log: EventLog,
    var settings: GameSettings? = null,
    var game: SavedGame? = null,
) : GameStore {
    val settingsSaves = mutableListOf<GameSettings>()
    val gameSaves = mutableListOf<SavedGame?>()

    override suspend fun loadSettings() = settings
    override suspend fun saveSettings(s: GameSettings) {
        settingsSaves += s
        settings = s
        log += "saveSettings"
    }

    override suspend fun loadGame() = game
    override suspend fun saveGame(g: SavedGame?) {
        gameSaves += g
        game = g
        log += "saveGame:${g?.currentSongId ?: if (g == null) "null" else "-"}"
    }
}

/** Songs in list order; next() never repeats one. [nextAllowed] caps how many next() calls succeed. */
class FakeSongSource(songs: List<Song>) : SongSource {
    private val catalog = Catalog(songs)
    private val played = mutableSetOf<String>()
    var nextAllowed = Int.MAX_VALUE

    override fun count(s: GameSettings) = catalog.filter(s).size
    override fun next(s: GameSettings): Song? {
        if (nextAllowed <= 0) return null
        nextAllowed--
        return catalog.filter(s).firstOrNull { it.id !in played }?.also { played += it.id }
    }

    override fun byId(id: String) = catalog.byId(id)
}

val seedCatalog: Catalog by lazy {
    Catalog.parse(FakeSongSource::class.java.classLoader!!.getResource("seed-catalog.json")!!.readText())
}

fun seedSong(id: String): Song = seedCatalog.byId(id) ?: error("no song $id")
