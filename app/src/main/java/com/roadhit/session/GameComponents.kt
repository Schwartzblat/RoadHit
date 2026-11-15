package com.roadhit.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.roadhit.R
import com.roadhit.audio.DeezerPreviews
import com.roadhit.audio.ExoClipPlayer
import com.roadhit.audio.TtsSpeaker
import com.roadhit.catalog.Catalog
import com.roadhit.catalog.SongPicker
import com.roadhit.game.ClipPlayer
import com.roadhit.game.Display
import com.roadhit.game.GameEngine
import com.roadhit.game.Lang
import com.roadhit.game.LocalAnswerJudge
import com.roadhit.game.Song
import com.roadhit.speech.GoogleListener
import com.roadhit.speech.MicSource
import java.io.ByteArrayOutputStream
import kotlin.random.Random

private const val TAG = "RoadHit"

/** The app's one Preferences DataStore; a corrupt file is replaced with empty preferences. */
private val Context.roadHitData: DataStore<Preferences> by preferencesDataStore(
    name = "roadhit",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

internal val Context.appDataStore: DataStore<Preferences> get() = applicationContext.roadHitData

/** Hebrew when the phone's language is Hebrew, else English. */
internal fun defaultLang(context: Context): Lang =
    when (context.resources.configuration.locales[0].language) {
        "he", "iw" -> Lang.HE
        else -> Lang.EN
    }

/** What lives as long as the service: the catalog, the song picker and Deezer lookups, over the shared stores. */
internal class GameComponents private constructor(
    val catalog: Catalog,
    val stores: AppStores,
    val songs: SongPicker,
    /** Shared by the clip player and the reveal's cover. */
    val previews: DeezerPreviews,
) {
    companion object {
        /** Reads the catalog asset and takes the shared stores ([appStores]); blocking work, call off the main thread. */
        suspend fun load(context: Context): GameComponents {
            val catalog = context.assets.open("catalog.json").bufferedReader().use { Catalog.parse(it.readText()) }
            val stores = context.appStores()
            val songs = SongPicker(catalog, stores, Random.Default) { GameService.debugNextSong?.invoke() }
            return GameComponents(catalog, stores, songs, DeezerPreviews())
        }
    }
}

/**
 * The audio side of one game run and the engine over it. A released [GoogleListener] cannot be
 * used again, so every run gets a fresh rig, released when the run ends.
 */
internal class GameRig(
    context: Context,
    components: GameComponents,
    display: Display,
    mic: () -> MicSource?,
    /** Called with each song as its clip starts. */
    onClip: (Song) -> Unit,
) {
    private val clips = ExoClipPlayer(context, components.previews)
    private val speaker = TtsSpeaker(context)
    private val listener = GoogleListener(context, mic)

    val engine = GameEngine(
        clips = ObservedClips(clips, onClip),
        speaker = speaker,
        listener = listener,
        display = display,
        store = components.stores,
        songs = components.songs,
        judge = LocalAnswerJudge(),
        prefs = { components.stores.prefs.value },
    )

    /** Idempotent; safe from any thread. */
    fun release() {
        clips.release()
        speaker.shutdown()
        listener.release()
    }
}

/** Tells [onClip] about each song as its clip starts. */
private class ObservedClips(private val inner: ClipPlayer, private val onClip: (Song) -> Unit) : ClipPlayer by inner {
    override suspend fun play(song: Song, maxSeconds: Int): Boolean {
        onClip(song)
        return inner.play(song, maxSeconds)
    }
}

/** The app logo as PNG bytes, for the media artwork; null if it cannot be drawn. */
internal fun renderLogo(context: Context, sizePx: Int = 320): ByteArray? = try {
    val drawable = context.getDrawable(R.drawable.ic_logo) ?: error("no logo")
    val bitmap = createBitmap(sizePx, sizePx)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(Canvas(bitmap))
    ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
    }
} catch (e: Exception) {
    Log.w(TAG, "logo render failed", e)
    null
}
