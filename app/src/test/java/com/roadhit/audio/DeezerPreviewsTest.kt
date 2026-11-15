package com.roadhit.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class DeezerPreviewsTest {

    private fun previews(body: String) = DeezerPreviews { body }

    @Test
    fun `normal track gives its preview`() = runTest {
        val json = """
            {"id":3135556,"readable":true,"title":"Harder, Better, Faster, Stronger",
             "duration":224,"preview":"https://cdnt-preview.dzcdn.net/api/1/1/a/b/c/0/abc.mp3?hdnea=exp=1",
             "artist":{"id":27,"name":"Daft Punk"}}
        """.trimIndent()
        assertEquals(
            "https://cdnt-preview.dzcdn.net/api/1/1/a/b/c/0/abc.mp3?hdnea=exp=1",
            previews(json).urlFor(3135556),
        )
    }

    @Test
    fun `error response gives null`() = runTest {
        val json = """{"error":{"type":"DataException","message":"no data","code":800}}"""
        assertNull(previews(json).urlFor(1))
    }

    @Test
    fun `empty preview gives null`() = runTest {
        assertNull(previews("""{"id":5,"preview":""}""").urlFor(5))
    }

    @Test
    fun `missing preview gives null`() = runTest {
        assertNull(previews("""{"id":5,"title":"x"}""").urlFor(5))
    }

    @Test
    fun `malformed json gives null`() = runTest {
        assertNull(previews("""{"id":5,"preview":""").urlFor(5))
        assertNull(previews("<html>Service unavailable</html>").urlFor(5))
        assertNull(previews("[]").urlFor(5))
    }

    @Test
    fun `network failure gives null`() = runTest {
        assertNull(DeezerPreviews { throw IOException("unreachable") }.urlFor(5))
    }

    @Test
    fun `cancellation is rethrown`() = runTest {
        try {
            DeezerPreviews { throw CancellationException("cancelled") }.urlFor(5)
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `requests the track url`() = runTest {
        val requested = mutableListOf<String>()
        DeezerPreviews { url -> requested += url; """{"preview":"p"}""" }.urlFor(3135556)
        assertEquals(listOf("https://api.deezer.com/track/3135556"), requested)
    }

    @Test
    fun `cover comes from the same lookup, with no request of its own`() = runTest {
        var requests = 0
        val previews = DeezerPreviews {
            requests++
            """{"id":7,"preview":"p","album":{"id":1,"cover_medium":"https://cdn/cover/250x250.jpg"}}"""
        }
        assertNull(previews.coverFor(7))
        assertEquals("p", previews.urlFor(7))
        assertEquals("https://cdn/cover/250x250.jpg", previews.coverFor(7))
        assertNull(previews.coverFor(8))
        assertEquals(1, requests)
    }

    @Test
    fun `missing or blank cover gives null`() = runTest {
        val noAlbum = DeezerPreviews { """{"preview":"p"}""" }
        noAlbum.urlFor(1)
        assertNull(noAlbum.coverFor(1))
        val blank = DeezerPreviews { """{"preview":"p","album":{"cover_medium":""}}""" }
        blank.urlFor(1)
        assertNull(blank.coverFor(1))
    }
}

class HttpGetTest {

    @Test
    fun `sends only a generic user agent and returns the body`() = runTest {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var userAgent: String? = null
        server.createContext("/track/1") { exchange ->
            userAgent = exchange.requestHeaders.getFirst("User-Agent")
            val body = """{"preview":"p"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val body = httpGet("http://127.0.0.1:${server.address.port}/track/1")
            assertEquals("""{"preview":"p"}""", body)
            assertEquals("RoadHit/1.0", userAgent)
        } finally {
            server.stop(0)
        }
    }
}
