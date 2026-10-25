package com.metrolist.desktop.playback

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * SponsorBlock client, ported from the Android app's SponsorBlockManager. Only the first four hex
 * characters of SHA-256(videoId) are sent, so the server cannot tell which track is playing.
 */
object SponsorBlock {
    private const val BASE_URL = "https://sponsor.ajay.app"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    val ALL_CATEGORIES = listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic", "filler")

    @Serializable
    data class Segment(
        val category: String,
        val segment: List<Double>,
        val actionType: String = "skip",
    ) {
        val startMs: Long get() = ((segment.getOrNull(0) ?: 0.0) * 1000).toLong()
        val endMs: Long get() = ((segment.getOrNull(1) ?: 0.0) * 1000).toLong()
    }

    @Serializable
    private data class HashPrefixEntry(
        val videoID: String,
        val segments: List<Segment> = emptyList(),
    )

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { isLenient = true; ignoreUnknownKeys = true }) }
            expectSuccess = false
        }
    }

    private val cache = ConcurrentHashMap<String, Pair<List<Segment>, Long>>()

    suspend fun segments(videoId: String, categories: Collection<String>): List<Segment> {
        if (videoId.isBlank() || categories.isEmpty()) return emptyList()
        val cached = cache[videoId]?.takeIf { System.currentTimeMillis() - it.second < CACHE_TTL_MS }?.first
        val all =
            cached ?: runCatching {
                client.get("$BASE_URL/api/skipSegments/${sha256(videoId).take(4)}") {
                    ALL_CATEGORIES.forEach { parameter("category", it) }
                    parameter("actionType", "skip")
                }.body<List<HashPrefixEntry>>()
            }.getOrElse { e ->
                Timber.tag("SponsorBlock").w("fetch failed for $videoId: ${e.message}")
                return emptyList()
            }.firstOrNull { it.videoID.equals(videoId, ignoreCase = true) }?.segments.orEmpty()
                .also { cache[videoId] = it to System.currentTimeMillis() }

        return all
            .filter { it.actionType == "skip" && it.category in categories && it.endMs > it.startMs }
            .sortedBy { it.startMs }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
