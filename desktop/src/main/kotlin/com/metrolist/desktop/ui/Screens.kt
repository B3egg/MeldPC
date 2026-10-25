package com.metrolist.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.YouTube.SearchFilter
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem

private val ContentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)

@Composable
fun HomeScreen(nav: Navigator) {
    val (load, retry) = rememberLoad(Unit) { YouTube.home() }
    LoadContent(load, retry) { page ->
        LazyColumn(contentPadding = ContentPadding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Text("Home", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
            items(page.sections.filter { it.items.isNotEmpty() }) { section ->
                Column {
                    SectionTitle(section.title, Modifier.padding(start = 8.dp, bottom = 4.dp))
                    LazyRow {
                        items(section.items) { item -> ItemCard(item) { nav.openItem(item) } }
                    }
                }
            }
        }
    }
}

private enum class SearchTab(val label: String, val filter: SearchFilter) {
    SONGS("Songs", SearchFilter.FILTER_SONG),
    VIDEOS("Videos", SearchFilter.FILTER_VIDEO),
    ALBUMS("Albums", SearchFilter.FILTER_ALBUM),
    ARTISTS("Artists", SearchFilter.FILTER_ARTIST),
    PLAYLISTS("Playlists", SearchFilter.FILTER_COMMUNITY_PLAYLIST),
}

@Composable
fun SearchScreen(nav: Navigator) {
    var text by rememberSaveable { mutableStateOf(nav.lastQuery) }
    var query by rememberSaveable { mutableStateOf(nav.lastQuery) }
    var tab by rememberSaveable { mutableStateOf(SearchTab.SONGS) }

    Column(Modifier.fillMaxSize().padding(ContentPadding)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Search songs, artists, albums") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier =
                Modifier.fillMaxWidth().onKeyEvent {
                    if (it.type == KeyEventType.KeyUp && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                        query = text.trim()
                        nav.lastQuery = query
                        true
                    } else {
                        false
                    }
                },
        )
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchTab.entries.forEach { t ->
                FilterChip(selected = t == tab, onClick = { tab = t }, label = { Text(t.label) })
            }
        }
        if (query.isBlank()) return@Column
        val (load, retry) = rememberLoad(query to tab) { YouTube.search(query, tab.filter) }
        LoadContent(load, retry) { result ->
            if (result.items.isEmpty()) {
                Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@LoadContent
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(result.items, key = { it.id }) { item -> ResultRow(item, nav) }
            }
        }
    }
}

@Composable
private fun ResultRow(item: YTItem, nav: Navigator) {
    if (item is SongItem) {
        SongRow(item, onClick = { nav.player.playRadio(item) }, actions = nav.songActions)
    } else {
        ItemListRow(item) { nav.openItem(item) }
    }
}

@Composable
private fun ItemListRow(item: YTItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        Artwork(item.thumbnail, 52.dp, if (item is ArtistItem) CircleShape else RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(item.title, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(item.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun CollectionScreen(nav: Navigator, target: Screen.Collection) {
    val (load, retry) =
        rememberLoad(target) {
            if (target.isAlbum) {
                YouTube.album(target.id).map { Triple(it.album.title, it.album.thumbnail, it.songs) to it.album.subtitle() }
            } else {
                YouTube.playlist(target.id).map { Triple(it.playlist.title, it.playlist.thumbnail, it.songs) to it.playlist.subtitle() }
            }
        }
    LoadContent(load, retry) { (info, subtitle) ->
        val (title, thumbnail, songs) = info
        LazyColumn(contentPadding = ContentPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                    Artwork(thumbnail, 180.dp)
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${songs.size} songs", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { nav.player.playQueue(songs) }, enabled = songs.isNotEmpty()) {
                                Icon(Icons.Default.PlayArrow, null)
                                Text("Play")
                            }
                            OutlinedButton(onClick = { nav.player.playQueue(songs.shuffled()) }, enabled = songs.isNotEmpty()) {
                                Icon(Icons.Default.Shuffle, null)
                                Text("Shuffle")
                            }
                        }
                    }
                }
            }
            itemsIndexed(songs) { i, song ->
                SongRow(song, onClick = { nav.player.playQueue(songs, i) }, actions = nav.songActions, index = i)
            }
        }
    }
}

@Composable
fun ArtistScreen(nav: Navigator, id: String) {
    val (load, retry) = rememberLoad(id) { YouTube.artist(id) }
    LoadContent(load, retry) { page ->
        LazyColumn(contentPadding = ContentPadding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(page.artist.thumbnail, 160.dp, CircleShape)
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Text(page.artist.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        (page.monthlyListenerCount ?: page.subscriberCountText)?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(12.dp))
                        page.artist.radioEndpoint?.let { radio ->
                            OutlinedButton(onClick = { nav.player.playEndpoint(radio) }) {
                                Icon(Icons.Default.Radio, null)
                                Text("Radio")
                            }
                        }
                    }
                }
            }
            items(page.sections.filter { it.items.isNotEmpty() }) { section ->
                Column {
                    SectionTitle(section.title, Modifier.padding(start = 8.dp, bottom = 4.dp))
                    val songs = section.items.filterIsInstance<SongItem>()
                    if (songs.size == section.items.size) {
                        songs.take(10).forEachIndexed { i, song ->
                            SongRow(song, onClick = { nav.player.playQueue(songs, i) }, actions = nav.songActions)
                        }
                    } else {
                        LazyRow { items(section.items) { item -> ItemCard(item) { nav.openItem(item) } } }
                    }
                }
            }
        }
    }
}
