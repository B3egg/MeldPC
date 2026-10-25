package com.metrolist.desktop.spotify

import com.metrolist.desktop.AppDirs
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the YouTube Music song to stream for a Spotify track, using the Android app's fuzzy
 * scoring (SpotifyMapper) and the same variant penalty for live/cover/sped-up uploads. Matches
 * are cached on disk, as the Android app does in its database.
 */
object SpotifyMatcher {
    private const val PLACEHOLDER_PREFIX = "spotify:"
    private const val MIN_MATCH_SCORE = 0.35
    private const val VARIANT_PENALTY = 0.15
    private const val MAX_VARIANT_PENALTY = 0.30
    private val VARIANT_MARKERS =
        Regex(
            "\\b(live|en vivo|en directo|ao vivo|karaoke|cover|instrumental|" +
                "sped up|spedup|slowed|nightcore|8d|music video|official video|lyric video)\\b",
        )

    @Serializable
    private data class Match(val youtubeId: String, val title: String, val artist: String)

    private val file = AppDirs.file("spotify-matches.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val saveLock = Mutex()
    private val matches: MutableMap<String, Match> =
        ConcurrentHashMap(runCatching { json.decodeFromString<Map<String, Match>>(file.readText()) }.getOrDefault(emptyMap()))

    /** Spotify tracks waiting to be matched, keyed by placeholder id. */
    private val tracks = ConcurrentHashMap<String, SpotifyTrack>()

    fun isPlaceholder(song: SongItem) = song.id.startsWith(PLACEHOLDER_PREFIX)

    fun trackFor(song: SongItem): SpotifyTrack? = tracks[song.id]

    /**
     * A queue entry for a Spotify track. It shows Spotify's title, artists and artwork, and is
     * matched to a YouTube video only when it is about to play.
     */
    fun placeholder(track: SpotifyTrack): SongItem {
        val id = PLACEHOLDER_PREFIX + track.id
        tracks[id] = track
        val matched = matches[track.id]
        return SongItem(
            id = matched?.youtubeId ?: id,
            title = track.name,
            artists = track.artists.map { Artist(name = it.name, id = null) },
            duration = track.durationMs / 1000,
            thumbnail = SpotifyMapper.getTrackThumbnail(track).orEmpty(),
            explicit = track.explicit,
        ).also { if (matched != null) tracks[it.id] = track }
    }

    /** Returns the playable song for [song], resolving Spotify placeholders through YouTube search. */
    suspend fun resolve(song: SongItem): SongItem? {
        if (!isPlaceholder(song)) return song
        val track = tracks[song.id] ?: return null
        val match = matches[track.id] ?: search(track) ?: return null
        tracks[match.youtubeId] = track
        return song.copy(id = match.youtubeId)
    }

    private suspend fun search(track: SpotifyTrack): Match? {
        val page = YouTube.searchSummary(SpotifyMapper.buildSearchQuery(track), incognito = true).getOrNull() ?: return null
        val precomputed = SpotifyMapper.precompute(track.name, track.artists.firstOrNull()?.name.orEmpty(), track.durationMs)
        val titleMarkers = VARIANT_MARKERS.findAll(track.name.lowercase()).map { it.value }.toSet()

        var best: SongItem? = null
        var bestScore = 0.0
        var bestAdjusted = Double.NEGATIVE_INFINITY
        for (song in page.summaries.flatMap { it.items }.filterIsInstance<SongItem>()) {
            val score =
                SpotifyMapper.matchScorePrecomputed(
                    precomputed = precomputed,
                    candidateTitle = song.title,
                    candidateArtist = song.artists.firstOrNull()?.name.orEmpty(),
                    candidateDurationSec = song.duration,
                )
            val extraMarkers = VARIANT_MARKERS.findAll(song.title.lowercase()).map { it.value }.toSet() - titleMarkers
            val adjusted = score - (extraMarkers.size * VARIANT_PENALTY).coerceAtMost(MAX_VARIANT_PENALTY)
            if (adjusted > bestAdjusted) {
                best = song
                bestScore = score
                bestAdjusted = adjusted
                if (adjusted >= SpotifyMapper.earlyExitThreshold()) break
            }
        }
        val chosen = best?.takeIf { bestScore >= MIN_MATCH_SCORE }
        if (chosen == null) {
            Timber.w("No YouTube match for Spotify track ${track.name}")
            return null
        }
        return Match(chosen.id, chosen.title, chosen.artists.firstOrNull()?.name.orEmpty()).also {
            matches[track.id] = it
            save()
        }
    }

    private suspend fun save() =
        saveLock.withLock {
            runCatching { file.writeText(json.encodeToString(matches.toMap())) }
                .onFailure { Timber.w(it, "Could not save Spotify matches") }
        }
}
