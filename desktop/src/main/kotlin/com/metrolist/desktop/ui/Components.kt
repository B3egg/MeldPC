package com.metrolist.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.desktop.spotify.SpotifyMatcher
import com.metrolist.desktop.youtube.YouTubeAccount
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem

@Composable
fun Artwork(url: String?, size: Dp, shape: Shape = RoundedCornerShape(6.dp)) {
    Box(
        Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

fun SongItem.artistText(): String = artists.joinToString(", ") { it.name }

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun YTItem.subtitle(): String =
    when (this) {
        is SongItem -> listOfNotNull(artistText().ifBlank { null }, album?.name).joinToString(" • ")
        is AlbumItem -> listOfNotNull("Album", artists?.joinToString(", ") { it.name }, year?.toString()).joinToString(" • ")
        is PlaylistItem -> listOfNotNull("Playlist", author?.name, songCountText).joinToString(" • ")
        is ArtistItem -> "Artist"
        else -> ""
    }

@Composable
fun SongRow(
    song: SongItem,
    onClick: () -> Unit,
    actions: SongActions,
    isCurrent: Boolean = false,
    index: Int? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text(
                "${index + 1}",
                modifier = Modifier.width(32.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Artwork(song.thumbnail, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                song.subtitle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        song.duration?.let {
            Text(
                formatDuration(it * 1000L),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SongMenu(song, actions)
    }
}

data class SongActions(
    val playNext: (SongItem) -> Unit,
    val addToQueue: (SongItem) -> Unit,
    val startRadio: (SongItem) -> Unit,
    val openArtist: (String) -> Unit,
    val openAlbum: (String) -> Unit,
)

@Composable
private fun SongMenu(song: SongItem, actions: SongActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Play next") }, onClick = { open = false; actions.playNext(song) })
            DropdownMenuItem(text = { Text("Add to queue") }, onClick = { open = false; actions.addToQueue(song) })
            DropdownMenuItem(text = { Text("Start radio") }, onClick = { open = false; actions.startRadio(song) })
            if (YouTubeAccount.isLoggedIn && !SpotifyMatcher.isPlaceholder(song)) {
                val liked = YouTubeAccount.isLiked(song.id)
                DropdownMenuItem(
                    text = { Text(if (liked) "Remove from liked songs" else "Like") },
                    onClick = { open = false; YouTubeAccount.toggleLike(song) },
                )
            }
            song.artists.firstOrNull { it.id != null }?.let { artist ->
                DropdownMenuItem(text = { Text("Go to artist") }, onClick = { open = false; actions.openArtist(artist.id!!) })
            }
            song.album?.let { album ->
                DropdownMenuItem(text = { Text("Go to album") }, onClick = { open = false; actions.openAlbum(album.id) })
            }
        }
    }
}

@Composable
fun ItemCard(item: YTItem, onClick: () -> Unit) =
    MediaCard(item.title, item.subtitle(), item.thumbnail, circle = item is ArtistItem, onClick = onClick)

@Composable
fun MediaCard(title: String, subtitle: String?, imageUrl: String?, circle: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.width(168.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(8.dp),
    ) {
        Artwork(imageUrl, 152.dp, if (circle) CircleShape else RoundedCornerShape(8.dp))
        Spacer(Modifier.size(8.dp))
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.size(12.dp))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = modifier)
}
