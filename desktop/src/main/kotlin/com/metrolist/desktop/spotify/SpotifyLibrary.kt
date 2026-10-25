package com.metrolist.desktop.spotify

import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyHomeFeed
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifySimpleAlbum
import com.metrolist.spotify.models.SpotifyTrack

/** Spotify content for the screens, signing in the request first. */
object SpotifyLibrary {
    private const val MAX_TRACKS = 500

    data class Collection(
        val title: String,
        val subtitle: String?,
        val imageUrl: String?,
        val tracks: List<SpotifyTrack>,
    )

    private suspend fun <T> authed(block: suspend () -> Result<T>): Result<T> =
        if (SpotifySession.ensureAuthenticated()) block() else Result.failure(IllegalStateException("Sign in to Spotify in Settings"))

    suspend fun home(): Result<SpotifyHomeFeed> = authed { Spotify.home() }

    suspend fun playlists(): Result<List<SpotifyPlaylist>> = authed { Spotify.myPlaylists(limit = 50).map { it.items } }

    suspend fun likedSongs(): Result<Collection> =
        authed {
            runCatching {
                val tracks = pages { offset -> Spotify.likedSongs(limit = 50, offset = offset).getOrThrow().let { p -> p.items.map { it.track } to p.total } }
                Collection("Liked Songs", "Spotify", null, tracks)
            }
        }

    suspend fun playlist(id: String): Result<Collection> =
        authed {
            runCatching {
                val info = Spotify.playlist(id).getOrThrow()
                val tracks = pages { offset -> Spotify.playlistTracks(id, limit = 100, offset = offset).getOrThrow().let { p -> p.items.mapNotNull { it.track } to p.total } }
                Collection(info.name, info.owner?.displayName?.let { "Playlist • $it" } ?: "Playlist", info.images.firstOrNull()?.url, tracks)
            }
        }

    suspend fun album(id: String): Result<Collection> =
        authed {
            Spotify.album(id).map { album ->
                // Album track listings leave out the album itself, which carries the artwork.
                val simple = SpotifySimpleAlbum(id = album.id, name = album.name, images = album.images, artists = album.artists)
                val tracks = album.tracks?.items.orEmpty().map { if (it.album == null) it.copy(album = simple) else it }
                val subtitle = listOfNotNull("Album", album.artists.joinToString(", ") { it.name }.ifBlank { null }, album.releaseDate?.take(4)).joinToString(" • ")
                Collection(album.name, subtitle, album.images.firstOrNull()?.url, tracks)
            }
        }

    suspend fun searchTracks(query: String): Result<List<SpotifyTrack>> =
        authed { Spotify.search(query, types = listOf("track"), limit = 30).map { it.tracks?.items.orEmpty() } }

    private suspend fun pages(fetch: suspend (offset: Int) -> Pair<List<SpotifyTrack>, Int>): List<SpotifyTrack> {
        val all = mutableListOf<SpotifyTrack>()
        while (all.size < MAX_TRACKS) {
            val (items, total) = fetch(all.size)
            all += items
            if (items.isEmpty() || all.size >= total) break
        }
        return all.filter { it.id.isNotEmpty() && !it.isLocal }
    }
}
