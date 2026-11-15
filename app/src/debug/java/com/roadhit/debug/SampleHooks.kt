package com.roadhit.debug

import android.content.Context
import com.roadhit.catalog.Catalog
import com.roadhit.game.FieldMatches
import com.roadhit.game.Heard
import com.roadhit.game.Lang
import com.roadhit.game.LocalAnswerJudge
import com.roadhit.game.Origin
import com.roadhit.game.RecordedAudio
import com.roadhit.speech.GoogleListener
import com.roadhit.speech.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private const val LISTEN_SECONDS = 12 // the game's turn window

/**
 * Recognition sample hook:
 *
 *   synth-judge --es id <song id>
 *       Speaks "<title.en> by <artist.en>" (international song, en-US voice) or
 *       "<title.he> של <artist.he>" (Israeli song, he-IL voice) into a WAV, plays it after a
 *       750 ms lead-in as the microphone of a GoogleListener (Hebrew pass), relistens to the kept
 *       audio in English, and judges the passes against the song with the game's judge.
 *       Result: `<id> <origin> said=<text> he=<json|unclear|silence|failed-…> en=<json> title=<b>
 *       artist=<b> first=<Tt><Aa> enOnly=<Tt><Aa>`: title/artist are the game's verdict (both
 *       passes; the English pass alone after a Hebrew no-match; nothing after silence or failure);
 *       `first` judges the Hebrew pass alone and `enOnly` the English pass alone (upper case = right).
 */
fun sampleHooks(context: Context): Map<String, LabHook> = mapOf(
    "synth-judge" to { args -> synthJudge(context, args) },
)

private val catalogLock = Mutex()
private var catalog: Catalog? = null

/** The bundled catalog, read once. */
internal suspend fun labCatalog(context: Context): Catalog = catalogLock.withLock {
    catalog ?: withContext(Dispatchers.IO) {
        context.assets.open("catalog.json").bufferedReader().use { Catalog.parse(it.readText()) }
    }.also { catalog = it }
}

private suspend fun synthJudge(context: Context, args: Map<String, String>): String {
    val id = args["id"] ?: throw IllegalArgumentException("missing --es id")
    val song = labCatalog(context).byId(id) ?: throw IllegalArgumentException("no song $id in the catalog")
    val (text, voice) = when (song.origin) {
        Origin.INTL -> "${song.title.en} by ${song.artist.en}" to "en-US"
        Origin.IL -> "${song.title.he} של ${song.artist.he}" to "he-IL"
    }
    val file = "sample.wav"
    val synthResult = synth(context, mapOf("text" to text, "lang" to voice, "file" to file))
    if ("error" in synthResult) return "$id synth $synthResult"
    val pcm = withContext(Dispatchers.IO) {
        val f = TestSet.file(context, file)
        Wav.read(f).data.also { f.delete() }
    }
    val played = ByteArray(LEAD_IN_BYTES) + pcm
    val listener = GoogleListener(context, { WavMicSource(played, thenSilence = true) })
    try {
        val heard = listener.listen(Lang.HE, LISTEN_SECONDS)
        // As the game: a Speech answer is judged over both passes; an Unclear one (Hebrew no-match)
        // from the English pass alone, or as silence when that is empty. After a silent or failed
        // Hebrew pass the English pass here is a diagnostic only.
        val hebrew = (heard as? Heard.Speech)?.transcripts.orEmpty()
        val kept = (heard as? Heard.Speech)?.audio ?: (heard as? Heard.Unclear)?.audio ?: RecordedAudio(played)
        val english = listener.relisten(kept, Lang.EN)
        val judge = LocalAnswerJudge()
        val game = when {
            heard is Heard.Speech -> judge.judge(song, hebrew + english, 0)
            heard is Heard.Unclear && english.isNotEmpty() -> judge.judge(song, english, 0)
            else -> FieldMatches(false, false, false)
        }
        val heText = when (heard) {
            is Heard.Speech -> json(hebrew)
            is Heard.Failed -> "failed-${heard.reason}"
            is Heard.Unclear -> "unclear"
            Heard.Silence -> "silence"
        }
        return "$id ${song.origin} said=$text he=$heText en=${json(english)} " +
            "title=${game.title} artist=${game.artist} " +
            "first=${flags(judge.judge(song, hebrew, 0))} enOnly=${flags(judge.judge(song, english, 0))}"
    } finally {
        listener.release()
    }
}

/** T/t title right or not, A/a artist right or not. */
private fun flags(m: FieldMatches) = (if (m.title) "T" else "t") + (if (m.artist) "A" else "a")

private fun json(alternatives: List<String>) = JsonArray(alternatives.map(::JsonPrimitive)).toString()
