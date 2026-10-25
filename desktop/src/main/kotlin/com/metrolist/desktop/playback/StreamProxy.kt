package com.metrolist.desktop.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Local HTTP server that VLC reads audio from. YouTube streams need custom request headers and,
 * for some clients, must be fetched in bounded byte ranges; VLC can do neither, so this proxy
 * applies them, re-resolves the stream when a URL expires or is rejected, and serves VLC a plain,
 * seekable HTTP resource.
 */
class StreamProxy(
    private val streams: suspend (videoId: String, forceRefresh: Boolean) -> ResolvedStream,
) {
    private val http =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()

    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r, "meld-stream").apply { isDaemon = true } }
            createContext("/audio/") { exchange -> exchange.use { handle(it) } }
            start()
        }

    fun urlFor(videoId: String): String = "http://127.0.0.1:${server.address.port}/audio/$videoId"

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val videoId = exchange.requestURI.path.removePrefix("/audio/").takeIf { it.isNotBlank() }
        if (videoId == null) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        val requested = parseRange(exchange.requestHeaders.getFirst("Range"))
        try {
            serve(exchange, videoId, requested)
        } catch (e: IOException) {
            // VLC closes connections whenever it seeks or stops; that is not an error.
            Timber.tag(TAG).d("Stream connection closed for $videoId: ${e.message}")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Stream failed for $videoId")
            runCatching { exchange.sendResponseHeaders(502, -1) }
        }
    }

    private fun serve(exchange: HttpExchange, videoId: String, requested: LongRange?) {
        var stream = runBlocking { streams(videoId, false) }
        val start = requested?.first ?: 0L

        // The first upstream request decides the response headers; a rejected URL is resolved again once.
        var first = openUpstream(stream, start, chunkEnd(stream, start, requested))
        if (first.statusCode() == 403 || first.statusCode() == 410) {
            first.body().close()
            StreamResolver.markClientFailed(videoId, stream.clientName)
            runBlocking { StreamResolver.refreshAfterRejection() }
            stream = runBlocking { streams(videoId, true) }
            first = openUpstream(stream, start, chunkEnd(stream, start, requested))
        }
        if (first.statusCode() !in 200..299) {
            first.body().close()
            exchange.sendResponseHeaders(502, -1)
            return
        }

        val total = stream.contentLength ?: totalFromContentRange(first) ?: first.headers().firstValueAsLong("Content-Length").orElse(-1)
            .takeIf { it >= 0 && first.statusCode() == 200 }
        val end = requested?.last?.takeIf { it != Long.MAX_VALUE } ?: total?.minus(1)

        exchange.responseHeaders.apply {
            add("Accept-Ranges", "bytes")
            add("Content-Type", stream.mimeType ?: "application/octet-stream")
            if (requested != null && total != null && end != null) add("Content-Range", "bytes $start-$end/$total")
        }
        val length = if (end != null) end - start + 1 else 0L
        exchange.sendResponseHeaders(if (requested != null) 206 else 200, length)

        exchange.responseBody.use { out ->
            var pos = start
            var response: HttpResponse<InputStream>? = first
            while (response != null) {
                pos += response.body().use { it.copyTo(out) }
                response = null
                if (stream.usesChunks && (end == null || pos <= end)) {
                    if (System.currentTimeMillis() >= stream.expiresAtMs) stream = runBlocking { streams(videoId, true) }
                    val next = openUpstream(stream, pos, chunkEnd(stream, pos, requested))
                    if (next.statusCode() == 416) {
                        next.body().close()
                    } else if (next.statusCode() in 200..299) {
                        response = next
                    } else {
                        next.body().close()
                        throw IOException("Upstream returned ${next.statusCode()} at byte $pos")
                    }
                }
            }
        }
    }

    private fun chunkEnd(stream: ResolvedStream, start: Long, requested: LongRange?): Long? {
        val wanted = requested?.last?.takeIf { it != Long.MAX_VALUE }
        val total = stream.contentLength?.minus(1)
        if (!stream.usesChunks) return wanted
        return listOfNotNull(start + stream.rangeChunkSizeBytes - 1, wanted, total).min()
    }

    private fun openUpstream(stream: ResolvedStream, start: Long, end: Long?): HttpResponse<InputStream> {
        val request =
            HttpRequest.newBuilder(URI(stream.url))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .apply {
                    stream.headers.forEach { (k, v) -> if (k.lowercase() !in RESTRICTED_HEADERS) header(k, v) }
                    if (start > 0 || end != null) header("Range", "bytes=$start-${end ?: ""}")
                }.build()
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun totalFromContentRange(response: HttpResponse<*>): Long? =
        response.headers().firstValue("Content-Range").orElse(null)
            ?.substringAfter('/')?.toLongOrNull()

    private fun parseRange(header: String?): LongRange? {
        val spec = header?.trim()?.removePrefix("bytes=")?.takeIf { it != header } ?: return null
        val (from, to) = spec.split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val start = from.toLongOrNull() ?: return null
        return start..(to.toLongOrNull() ?: Long.MAX_VALUE)
    }

    private companion object {
        const val TAG = "StreamProxy"
        val RESTRICTED_HEADERS = setOf("host", "connection", "content-length", "expect", "upgrade")
    }
}
