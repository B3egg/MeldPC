package com.metrolist.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem

@Composable
fun LibraryScreen(nav: Navigator) {
    val state by SpotifySession.state.collectAsState()
    if (state !is SpotifySession.State.LoggedIn) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Your library", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Sign in to Spotify to see your liked songs and playlists here.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { nav.selectTab(Screen.Settings) }) { Text("Go to Settings") }
        }
        return
    }
    val (load, retry) = rememberLoad(state) { SpotifyLibrary.playlists() }
    LoadContent(load, retry) { playlists ->
        LazyVerticalGrid(columns = GridCells.Adaptive(176.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
            }
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
