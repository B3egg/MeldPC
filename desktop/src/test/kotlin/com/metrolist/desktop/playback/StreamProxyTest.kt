package com.metrolist.desktop.playback

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamProxyTest {
    private val content = Random(42).nextBytes(10_000)
    private val upstreamRanges = mutableListOf<String>()

    // Fake googlevideo server: insists on a header and on bounded ranges of at most 4096 bytes.
    private val upstream =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/videoplayback") { ex ->
                ex.use {
                    val range = ex.requestHeaders.getFirst("Range")
                    synchronized(upstreamRanges) { upstreamRanges += range.orEmpty() }
                    if (ex.requestHeaders.getFirst("X-Test") != "yes") return@use ex.sendResponseHeaders(403, -1)
                    val (start, end) = range!!.removePrefix("bytes=").split('-').let { it[0].toInt() to it[1].toInt() }
                    if (start >= content.size) return@use ex.sendResponseHeaders(416, -1)
                    if (end - start + 1 > 4096) return@use ex.sendResponseHeaders(403, -1)
                    val last = minOf(end, content.lastIndex)
                    ex.responseHeaders.add("Content-Range", "bytes $start-$last/${content.size}")
                    ex.sendResponseHeaders(206, (last - start + 1).toLong())
                    ex.responseBody.write(content, start, last - start + 1)
                }
            }
            start()
        }

    private val stream =
        ResolvedStream(
            videoId = "abc",
            url = "http://127.0.0.1:${upstream.address.port}/videoplayback",
            headers = mapOf("X-Test" to "yes"),
            contentLength = content.size.toLong(),
            mimeType = "audio/webm",
            clientName = "TEST",
            expiresAtMs = Long.MAX_VALUE,
            requireBoundedRange = true,
            rangeChunkSizeBytes = 4096,
            useRangeChunks = true,
            loudnessDb = null,
        )

    private val proxy = StreamProxy { _, _ -> stream }

    @AfterTest
    fun tearDown() {
        proxy.stop()
        upstream.stop(0)
    }

    private fun fetch(range: String?): Triple<Int, (String) -> String?, ByteArray> {
        val conn = URI(proxy.urlFor("abc")).toURL().openConnection() as HttpURLConnection
        range?.let { conn.setRequestProperty("Range", it) }
        return Triple(conn.responseCode, conn::getHeaderField, conn.inputStream.use { it.readBytes() })
    }

    @Test
    fun servesWholeStreamInBoundedChunks() {
        val (code, headers, body) = fetch(null)
        assertEquals(200, code)
        assertEquals("10000", headers("Content-Length"))
        assertContentEquals(content, body)
        assertEquals<List<String>>(listOf("bytes=0-4095", "bytes=4096-8191", "bytes=8192-9999"), upstreamRanges.toList())
    }

    @Test
    fun servesOpenEndedRangeForSeeking() {
        val (code, headers, body) = fetch("bytes=5000-")
        assertEquals(206, code)
        assertEquals("bytes 5000-9999/10000", headers("Content-Range"))
        assertContentEquals(content.copyOfRange(5000, 10_000), body)
    }

    @Test
    fun servesClosedRange() {
        val (code, _, body) = fetch("bytes=100-199")
        assertEquals(206, code)
        assertContentEquals(content.copyOfRange(100, 200), body)
        assertTrue(upstreamRanges.all { it == "bytes=100-199" })
    }
}
