package com.metrolist.desktop.spotify

import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Port of the Android app's SpotifyRecommendationEngine. Builds a radio-like queue for a seed
 * track from four candidate sources (seed artist top tracks, same album, genre-neighbour artists
 * from the user's profile, and the user's own top tracks), scores them by source, artist
 * affinity, genre overlap and recency, then caps tracks per artist and interleaves sources.
 */
object SpotifyRecommender {
    private const val MAX_TRACKS_PER_ARTIST = 3
    private const val W_SOURCE = 0.28f
    private const val W_AFFINITY = 0.33f
    private const val W_GENRE = 0.22f
    private const val W_RECENCY = 0.17f

    private enum class Bucket(val sourceScore: Float) { SEED_ARTIST(1.0f), SAME_ALBUM(0.85f), GENRE_NEIGHBOR(0.65f), USER_TOP(0.45f) }

    private class Profile(
        val affinity: Map<String, Float>,
        val genres: Map<String, Set<String>>,
        val topTracks: List<SpotifyTrack>,
        val recentArtists: Set<String>,
    )

    private class Candidate(val track: SpotifyTrack, val bucket: Bucket, val score: Float)

    suspend fun recommendations(seed: SpotifyTrack, limit: Int = 50): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            if (!SpotifySession.ensureAuthenticated()) return@withContext emptyList()
            val profile = buildProfile(SpotifyProfile.get())
            val seedArtists = seed.artists.mapNotNull { it.id }.toSet()
            val seedGenres = seedArtists.flatMap { profile.genres[it].orEmpty() }.toSet()
            val neighbours = genreNeighbours(profile, seedArtists, seedGenres)

            val (seedArtistTracks, albumTracks, neighbourTracks) =
                coroutineScope {
                    val bySeed = seedArtists.take(2).map { async { Spotify.artistTopTracks(it).getOrNull()?.tracks.orEmpty() } }
                    val byAlbum = async { seed.album?.id?.takeIf { it.isNotEmpty() }?.let { Spotify.album(it).getOrNull()?.tracks?.items }.orEmpty() }
                    val byNeighbour = neighbours.take(4).map { async { Spotify.artistTopTracks(it).getOrNull()?.tracks.orEmpty() } }
                    Triple(bySeed.awaitAll().flatten(), byAlbum.await(), byNeighbour.awaitAll().flatten())
                }

            val seen = mutableSetOf(seed.id)
            val candidates = mutableListOf<Candidate>()
            fun add(tracks: List<SpotifyTrack>, bucket: Bucket) =
                tracks.filter { it.id.isNotEmpty() && seen.add(it.id) }
                    .forEach { candidates += Candidate(it, bucket, score(profile, it, bucket, seedGenres)) }
            add(seedArtistTracks, Bucket.SEED_ARTIST)
            add(albumTracks, Bucket.SAME_ALBUM)
            add(neighbourTracks, Bucket.GENRE_NEIGHBOR)
            add(profile.topTracks, Bucket.USER_TOP)
            Timber.d("Spotify radio for '${seed.name}': ${candidates.size} candidates")
            diversify(candidates.sortedByDescending { it.score }, limit)
        }

    private fun buildProfile(data: SpotifyProfile.Data?): Profile {
        if (data == null) return Profile(emptyMap(), emptyMap(), emptyList(), emptySet())
        val artists = data.topArtists
        val tracks = data.tasteTracks.take(100)
        val affinity = mutableMapOf<String, Float>()
        artists.forEachIndexed { i, a ->
            if (a.id.isNotEmpty()) affinity.merge(a.id, (1f - i.toFloat() / artists.size.coerceAtLeast(1)) * 2f, Float::plus)
        }
        tracks.forEachIndexed { i, t ->
            t.artists.mapNotNull { it.id }.forEach { affinity.merge(it, 1f - i.toFloat() / tracks.size.coerceAtLeast(1), Float::plus) }
        }
        val max = affinity.values.maxOrNull()?.takeIf { it > 0f } ?: 1f
        return Profile(
            affinity = affinity.mapValues { it.value / max },
            genres = artists.filter { it.id.isNotEmpty() && it.genres.isNotEmpty() }.associate { it.id to it.genres.toSet() },
            topTracks = tracks,
            recentArtists = artists.take(10).map { it.id }.filter { it.isNotEmpty() }.toSet(),
        )
    }

    private fun score(profile: Profile, track: SpotifyTrack, bucket: Bucket, seedGenres: Set<String>): Float {
        val ids = track.artists.mapNotNull { it.id }
        val affinity = ids.maxOfOrNull { profile.affinity[it] ?: 0f } ?: 0f
        val genres = ids.flatMap { profile.genres[it].orEmpty() }.toSet()
        val overlap = if (seedGenres.isEmpty() || genres.isEmpty()) 0f else seedGenres.intersect(genres).size.toFloat() / seedGenres.union(genres).size
        val recency = if (ids.any { it in profile.recentArtists }) 1f else 0f
        return W_SOURCE * bucket.sourceScore + W_AFFINITY * affinity + W_GENRE * overlap + W_RECENCY * recency
    }

    private fun genreNeighbours(profile: Profile, seedArtists: Set<String>, seedGenres: Set<String>): List<String> {
        if (seedGenres.isEmpty()) {
            return profile.affinity.filterKeys { it !in seedArtists }.entries.sortedByDescending { it.value }.take(5).map { it.key }
        }
        return profile.genres
            .filterKeys { it !in seedArtists }
            .map { (id, genres) ->
                val union = seedGenres.union(genres).size
                val jaccard = if (union > 0) seedGenres.intersect(genres).size.toFloat() / union else 0f
                id to (jaccard * 0.6f + (profile.affinity[id] ?: 0f) * 0.4f)
            }
            .filter { it.second > 0.05f }
            .sortedByDescending { it.second }
            .take(6)
            .map { it.first }
    }

    private fun diversify(ranked: List<Candidate>, limit: Int): List<SpotifyTrack> {
        val result = mutableListOf<SpotifyTrack>()
        val perArtist = mutableMapOf<String, Int>()
        val recentBuckets = ArrayDeque<Bucket>()
        val used = BooleanArray(ranked.size)
        while (result.size < limit) {
            // After three picks from the same source, prefer a different one.
            val wantDifferent = recentBuckets.size >= 3 && recentBuckets.takeLast(3).distinct().size == 1
            var pick = -1
            for (i in ranked.indices) {
                if (used[i]) continue
                val artist = ranked[i].track.artists.firstOrNull()?.id.orEmpty()
                if ((perArtist[artist] ?: 0) >= MAX_TRACKS_PER_ARTIST) continue
                if (!wantDifferent || ranked[i].bucket != recentBuckets.last()) {
                    pick = i
                    break
                }
                if (pick == -1) pick = i
            }
            if (pick == -1) break
            used[pick] = true
            val chosen = ranked[pick]
            result += chosen.track
            perArtist.merge(chosen.track.artists.firstOrNull()?.id.orEmpty(), 1, Int::plus)
            recentBuckets.addLast(chosen.bucket)
            if (recentBuckets.size > 5) recentBuckets.removeFirst()
        }
        return result
    }
}
