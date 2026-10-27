package com.metrolist.desktop.playback

import com.metrolist.desktop.AppDirs
import com.metrolist.innertube.YouTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

data class ResolvedStream(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    val contentLength: Long?,
    val mimeType: String?,
    val clientName: String,
    val expiresAtMs: Long,
    val requireBoundedRange: Boolean,
    val rangeChunkSizeBytes: Long,
    val useRangeChunks: Boolean,
    val loudnessDb: Double?,
    /** YouTube's playback-stats URL; pinging it adds the song to the account's history. */
    val playbackUrl: String? = null,
) {
    val usesChunks: Boolean
        get() = (requireBoundedRange || useRangeChunks) && rangeChunkSizeBytes > 0
}

/**
 * Desktop counterpart of the Android app's InnerTubeXPlayer: turns a video id into a direct,
 * deciphered audio stream. There is no WebView on desktop, so no PO token provider is used and
 * InnerTubeX falls back to the clients that work without one.
 */
object StreamResolver {
    private const val TAG = "StreamResolver"
    private const val FAILURE_TTL_MS = 5 * 60 * 1000L
    private const val DEFAULT_TTL_MS = 5 * 60 * 1000L

    private val mutex = Mutex()
    private var bundle: Bundle? = null
    private val failedClients = ConcurrentHashMap<String, Pair<Set<String>, Long>>()

    var audioQuality: AudioQuality = AudioQuality.AUTO

    suspend fun resolve(videoId: String): Result<ResolvedStream> =
        try {
            val hints =
                ContentHints().withStreamCapabilities(
                    allowHls = false,
                    allowSabr = false,
                    allowBoundedRange = true,
                )
            val stream =
                requireNotNull(
                    bundle().extractor.extract(
                        videoId = videoId,
                        hints = hints,
                        excludedClients = excludedClients(videoId),
                        audioQuality = audioQuality,
                        clientPlaybackNonce = generateClientPlaybackNonce(),
                    ),
                ) { "No playable stream was found" }
            check(stream.sabrBootstrap == null) { "SABR streams are not supported" }
            val now = Clock.System.now().toEpochMilliseconds()
            Result.success(
                ResolvedStream(
                    videoId = videoId,
                    url = stream.audioUrl,
                    headers = stream.headers,
                    contentLength = stream.contentLengthBytes,
                    mimeType = stream.mimeType,
                    clientName = stream.clientName,
                    expiresAtMs = stream.expiresAt?.toEpochMilliseconds() ?: (now + DEFAULT_TTL_MS),
                    requireBoundedRange = stream.requireBoundedRange,
                    rangeChunkSizeBytes = stream.rangeChunkSizeBytes,
                    useRangeChunks = stream.useRangeChunks,
                    loudnessDb = stream.loudnessDb,
                    playbackUrl = stream.playbackTracking?.playbackUrl,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamResolveException) {
            val cause = e.cause
            Result.failure(if (e.reason == StreamResolveException.Reason.NETWORK && cause != null) cause else e)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Called when the stream URL from [client] was rejected, so the next resolve tries another client. */
    fun markClientFailed(videoId: String, client: String) {
        failedClients.compute(videoId) { _, old ->
            (old?.first.orEmpty() + client) to System.currentTimeMillis()
        }
    }

    suspend fun refreshAfterRejection(): Boolean = bundle().cipherService.refreshAfterStreamRejection()

    private fun excludedClients(videoId: String): Set<String> {
        val (clients, at) = failedClients[videoId] ?: return emptySet()
        if (System.currentTimeMillis() - at > FAILURE_TTL_MS) {
            failedClients.remove(videoId)
            return emptySet()
        }
        return clients
    }

    private suspend fun bundle(): Bundle {
        val transport = YouTube.extractionTransport()
        bundle?.takeIf { it.generation == transport.generation }?.let { return it }
        return mutex.withLock {
            val latest = YouTube.extractionTransport()
            bundle?.takeIf { it.generation == latest.generation }?.let { return@withLock it }
            runCatching { bundle?.cipherService?.dispose() }
            val remoteStore = RemotePlayerConfigStore(latest.httpClient, FilePlayerConfigRepository, logger)
            val cipherService = YouTubeCipherService(latest.httpClient, remoteStore, logger)
            val parser = YtConfigParserImpl(latest.httpClient, latest.innerTube, remoteStore, logger)
            val extractor =
                InnerTubeExtractor(
                    configParser = parser.withEmbeddedConfigFallback(),
                    cipherService = cipherService,
                    innerTube = latest.innerTube,
                    logger = logger,
                )
            Bundle(latest.generation, cipherService, extractor).also { bundle = it }
        }
    }

    private fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                try {
                    this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        }

    private val logger =
        InnerTubeLogger { event ->
            val message =
                event.message +
                    if (event.details.isEmpty()) "" else event.details.entries.joinToString(prefix = " [", postfix = "]")
            val tree = Timber.tag(event.tag)
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> tree.d(message)
                InnerTubeLogLevel.INFO -> tree.i(message)
                InnerTubeLogLevel.WARN -> tree.w(message)
                InnerTubeLogLevel.ERROR -> tree.e(message)
            }
        }

    private class Bundle(
        val generation: Long,
        val cipherService: YouTubeCipherService,
        val extractor: InnerTubeExtractor,
    )

    /** Persists the remote cipher config next to the app's other data, like SharedPreferences on Android. */
    private object FilePlayerConfigRepository : PlayerConfigRepository {
        @Serializable
        private data class Stored(
            val json: String = "",
            val cachedAtMs: Long = 0L,
            val sourceUrl: String = "",
            val etag: String = "",
        )

        private const val PLAYER_CONFIG_URL =
            "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

        private val file = AppDirs.file("player_config.json")
        private val codec = Json { ignoreUnknownKeys = true }

        @Volatile
        private var stored: Stored =
            runCatching { codec.decodeFromString<Stored>(file.readText()) }.getOrDefault(Stored())

        private fun update(transform: (Stored) -> Stored) {
            synchronized(this) {
                stored = transform(stored)
                runCatching { file.writeText(codec.encodeToString(stored)) }
                    .onFailure { Timber.tag(TAG).w(it, "Could not save player config") }
            }
        }

        override val enabled = true
        override val sourceUrl = PLAYER_CONFIG_URL
        override val defaultSourceUrl = PLAYER_CONFIG_URL
        override var cachedJson: String
            get() = stored.json
            set(value) = update { it.copy(json = value) }
        override var cachedAtMs: Long
            get() = stored.cachedAtMs
            set(value) = update { it.copy(cachedAtMs = value) }
        override var cachedSourceUrl: String
            get() = stored.sourceUrl
            set(value) = update { it.copy(sourceUrl = value) }
        override var cachedEtag: String
            get() = stored.etag
            set(value) = update { it.copy(etag = value) }
    }
}
