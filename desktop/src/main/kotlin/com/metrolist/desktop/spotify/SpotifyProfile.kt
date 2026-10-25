package com.metrolist.desktop.spotify

import com.metrolist.desktop.AppDirs
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * The user's Spotify taste profile, adapted from the Android app's SpotifyProfileCache: liked
 * songs through GraphQL (never rate limited) plus REST top tracks and artists when Spotify allows
 * it. Kept on disk so Home has content immediately after a restart.
 */
object SpotifyProfile {
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private const val DEGRADED_TTL_MS = 30 * 60 * 1000L

    @Serializable
    data class Data(
        val topTracks: List<SpotifyTrack> = emptyList(),
        val topArtists: List<SpotifyArtist> = emptyList(),
        val likedSongs: List<SpotifyTrack> = emptyList(),
        val fetchedAtMs: Long = 0,
        val hadRest: Boolean = false,
    ) {
        /** Top tracks first, then liked songs, without duplicates. */
        val tasteTracks: List<SpotifyTrack>
            get() = (topTracks + likedSongs).distinctBy { it.id }.filter { it.id.isNotEmpty() }
    }

    private val file = AppDirs.file("spotify-profile.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    private var cached: Data? = runCatching { json.decodeFromString<Data>(file.readText()) }.getOrNull()

    suspend fun get(forceRefresh: Boolean = false): Data? =
        mutex.withLock {
            val current = cached
            val ttl = if (current?.hadRest == true) TTL_MS else DEGRADED_TTL_MS
            if (!forceRefresh && current != null && System.currentTimeMillis() - current.fetchedAtMs < ttl) return current
            if (!SpotifySession.ensureAuthenticated()) return current
            (refresh() ?: current).also { cached = it }
        }

    fun clear() {
        cached = null
        file.delete()
    }

    private suspend fun refresh(): Data? =
        coroutineScope {
            val liked = async { Spotify.likedSongs(limit = 100).getOrNull()?.items?.map { it.track }.orEmpty() }
            val topTracks = async { Spotify.topTracks("short_term", limit = 50).getOrNull()?.items }
            val topArtists = async { Spotify.topArtists("medium_term", limit = 50).getOrNull()?.items }

            val likedSongs = liked.await()
            val restTracks = topTracks.await()
            val restArtists = topArtists.await()
            val tracks = restTracks.orEmpty()

            // Without REST top artists, rank artists by how often they appear in the user's tracks.
            val artists =
                restArtists ?: (tracks + likedSongs)
                    .flatMap { it.artists }
                    .filter { !it.id.isNullOrEmpty() }
                    .groupBy { it.id!! }
                    .entries
                    .sortedByDescending { it.value.size }
                    .take(30)
                    .map { (id, list) -> SpotifyArtist(id = id, name = list.first().name) }

            if (tracks.isEmpty() && likedSongs.isEmpty() && artists.isEmpty()) {
                Timber.w("Spotify profile: no data from any source")
                return@coroutineScope null
            }

            // Fill in pictures for the artists Home shows first.
            val withImages =
                artists.mapIndexed { i, artist ->
                    async {
                        if (i < 12 && artist.images.isEmpty()) Spotify.artist(artist.id).getOrNull()?.let { artist.copy(images = it.images, genres = artist.genres.ifEmpty { it.genres }) } ?: artist else artist
                    }
                }.awaitAll()

            Data(tracks, withImages, likedSongs, System.currentTimeMillis(), hadRest = restTracks != null)
                .also { data -> runCatching { file.writeText(json.encodeToString(data)) } }
        }
}
