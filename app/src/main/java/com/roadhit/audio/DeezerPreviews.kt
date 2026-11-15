package com.roadhit.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up a track's 30-second preview URL on Deezer. The URL is signed and expires, so
 * fetch it right before playing and never keep it beyond one play.
 *
 * Only `preview` and `album.cover_medium` are read, never `release_date`. The cover is kept
 * from the same response (no request of its own) for the reveal; nothing is shown before it.
 */
class DeezerPreviews(private val http: suspend (String) -> String = ::httpGet) {

    /** Album covers from recent lookups, by Deezer id. Guarded by itself. */
    private val covers = object : LinkedHashMap<Long, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > MAX_COVERS
    }

    /**
     * The album cover URL (`album.cover_medium`) seen by the last [urlFor] of this track; null when
     * it had none or the track was not looked up. Makes no request.
     */
    fun coverFor(deezerId: Long): String? = synchronized(covers) { covers[deezerId] }

    /** The preview URL; null when the track has none, Deezer reports an error, or the request fails. */
    suspend fun urlFor(deezerId: Long): String? {
        val body = try {
            http("https://api.deezer.com/track/$deezerId")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val track = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        if ("error" in track) return null
        val cover = ((track["album"] as? JsonObject)?.get("cover_medium") as? JsonPrimitive).nonBlankString()
        synchronized(covers) { if (cover == null) covers.remove(deezerId) else covers[deezerId] = cover }
        return (track["preview"] as? JsonPrimitive).nonBlankString()
    }

    private fun JsonPrimitive?.nonBlankString(): String? =
        this?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private companion object {
        const val MAX_COVERS = 16
    }
}

/**
 * A plain HTTP GET returning the body; throws on a network error or a non-2xx status.
 * The User-Agent is pinned: Android's default names the device model and OS build.
 */
suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private const val HTTP_TIMEOUT_MS = 8_000
/** Sent on every request the app makes (API and clip stream): Android's default names the device model and OS build. */
internal const val USER_AGENT = "RoadHit/1.0"
