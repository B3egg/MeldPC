package com.metrolist.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.spotify.SpotifyLibrary
import com.metrolist.desktop.spotify.SpotifyMatcher
import com.metrolist.desktop.spotify.SpotifySession
import com.metrolist.desktop.youtube.YouTubeAccount
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.utils.completed

@Composable
fun LibraryScreen(nav: Navigator) {
    val youtube by YouTubeAccount.state.collectAsState()
    val spotify by SpotifySession.state.collectAsState()
    val youtubeIn = youtube is YouTubeAccount.State.LoggedIn
    val spotifyIn = spotify is SpotifySession.State.LoggedIn
    if (!youtubeIn && !spotifyIn) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Your library", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Sign in to YouTube Music or Spotify to see your liked songs and playlists here.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { nav.selectTab(Screen.Settings) }) { Text("Go to Settings") }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        if (youtubeIn) {
            item(key = "yt-playlists") {
                LibraryRow(
                    "YouTube Music playlists",
                    youtube,
                    load = {
                        YouTube.library("FEmusic_liked_playlists").completed().map { page ->
                            page.items.filterIsInstance<PlaylistItem>().filterNot { it.id == "LM" || it.id == "SE" }
                        }
                    },
                    showWhenEmpty = true,
                ) { playlists ->
                    item { MediaCard("Liked Songs", "YouTube Music", null) { nav.push(Screen.Collection("LM", isAlbum = false)) } }
                    items(playlists) { playlist -> ItemCard(playlist) { nav.openItem(playlist) } }
                }
            }
            item(key = "yt-history") {
                LibraryRow("Recently played", youtube, load = {
                    YouTube.musicHistory().map { page -> page.sections.orEmpty().flatMap { it.songs }.distinctBy { it.id }.take(30) }
                }) { songs ->
                    itemsIndexed(songs) { i, song -> ItemCard(song) { nav.player.playQueue(songs, i) } }
                }
            }
            item(key = "yt-albums") {
                LibraryRow("Saved albums", youtube, load = {
                    YouTube.library("FEmusic_liked_albums").completed().map { it.items.filterIsInstance<AlbumItem>() }
                }) { albums ->
                    items(albums) { album -> ItemCard(album) { nav.openItem(album) } }
                }
            }
            item(key = "yt-artists") {
                LibraryRow("Artists", youtube, load = {
                    YouTube.library("FEmusic_library_corpus_artists").completed().map { it.items.filterIsInstance<ArtistItem>() }
                }) { artists ->
                    items(artists) { artist -> ItemCard(artist) { nav.openItem(artist) } }
                }
            }
        }
        if (spotifyIn) {
            item(key = "spotify-playlists") {
                LibraryRow("Spotify playlists", spotify, { SpotifyLibrary.playlists() }, showWhenEmpty = true) { playlists ->
                    item {
                        MediaCard("Liked Songs", "Spotify", null) {
                            nav.push(Screen.SpotifyCollection(Screen.SpotifyCollection.Kind.LIKED))
                        }
                    }
                    items(playlists) { playlist ->
                        MediaCard(playlist.name, playlist.owner?.displayName, playlist.images.firstOrNull()?.url) {
                            nav.push(Screen.SpotifyCollection(Screen.SpotifyCollection.Kind.PLAYLIST, playlist.id))
                        }
                    }
                }
            }
        }
    }
}

/** A titled row of cards that loads on its own; hidden when it turns out to be empty, unless [showWhenEmpty]. */
@Composable
private fun <T> LibraryRow(
    title: String,
    key: Any?,
    load: suspend () -> Result<List<T>>,
    showWhenEmpty: Boolean = false,
    content: LazyListScope.(List<T>) -> Unit,
) {
    val (state, retry) = rememberLoad(key, load)
    when (state) {
        Load.Loading -> Column {
            SectionTitle(title, Modifier.padding(start = 8.dp, bottom = 4.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is Load.Failed -> Column {
            SectionTitle(title, Modifier.padding(start = 8.dp, bottom = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = retry) { Text("Try again") }
            }
        }
        is Load.Ready -> if (showWhenEmpty || state.value.isNotEmpty()) CardRow(title) { content(state.value) }
    }
}

@Composable
fun SpotifyCollectionScreen(nav: Navigator, target: Screen.SpotifyCollection) {
    val (load, retry) =
        rememberLoad(target) {
            when (target.kind) {
                Screen.SpotifyCollection.Kind.PLAYLIST -> SpotifyLibrary.playlist(target.id)
                Screen.SpotifyCollection.Kind.ALBUM -> SpotifyLibrary.album(target.id)
                Screen.SpotifyCollection.Kind.LIKED -> SpotifyLibrary.likedSongs()
            }
        }
    LoadContent(load, retry) { collection ->
        val songs = remember(collection) { collection.tracks.map(SpotifyMatcher::placeholder) }
        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { CollectionHeader(collection.title, collection.subtitle, collection.imageUrl ?: songs.firstOrNull()?.thumbnail, songs, nav) }
            itemsIndexed(songs) { i, song ->
                SongRow(song, onClick = { nav.player.playQueue(songs, i) }, actions = nav.songActions, index = i)
            }
        }
    }
}

/** Opens the YouTube Music page of an artist known only by name (e.g. from Spotify). */
@Composable
fun ArtistByNameScreen(nav: Navigator, name: String) {
    val (load, retry) =
        rememberLoad(name) {
            YouTube.search(name, YouTube.SearchFilter.FILTER_ARTIST).mapCatching { result ->
                val artists = result.items.filterIsInstance<ArtistItem>()
                (artists.firstOrNull { it.title.equals(name, ignoreCase = true) } ?: artists.firstOrNull())?.id
                    ?: error("$name wasn't found on YouTube Music")
            }
        }
    LoadContent(load, retry) { id -> ArtistScreen(nav, id) }
}
