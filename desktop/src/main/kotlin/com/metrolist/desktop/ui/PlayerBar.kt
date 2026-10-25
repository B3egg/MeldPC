package com.metrolist.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.playback.PlayerController
import com.metrolist.desktop.playback.PlayerState
import com.metrolist.desktop.playback.RepeatMode

@Composable
fun PlayerBar(
    state: PlayerState,
    player: PlayerController,
    queueOpen: Boolean,
    onToggleQueue: () -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val song = state.current ?: return
    Row(
        Modifier.fillMaxWidth().height(88.dp).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Now playing
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.thumbnail, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                val artist = song.artists.firstOrNull { it.id != null }
                Text(
                    song.artistText(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (artist != null) Modifier.clickableNoRipple { onOpenArtist(artist.id!!) } else Modifier,
                )
                val status =
                    state.error
                        ?: state.skippedSegment?.let { "Skipped ${it.replace('_', ' ')} segment" }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }

        // Transport + seek bar
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = player::cycleRepeat) {
                    Icon(
                        if (state.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat",
                        tint = if (state.repeatMode == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = player::previous) { Icon(Icons.Default.SkipPrevious, "Previous") }
                FilledIconButton(onClick = player::togglePlay, modifier = Modifier.size(44.dp)) {
                    if (state.isLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                    }
                }
                IconButton(onClick = player::next, enabled = state.hasNext || state.repeatMode == RepeatMode.ALL) {
                    Icon(Icons.Default.SkipNext, "Next")
                }
                Spacer(Modifier.width(40.dp))
            }
            SeekBar(state, player)
        }

        // Volume + queue
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Volume", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = state.volume.toFloat(),
                onValueChange = { player.setVolume(it.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.width(120.dp),
            )
            IconButton(onClick = onToggleQueue) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    "Queue",
                    tint = if (queueOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SeekBar(state: PlayerState, player: PlayerController) {
    // While dragging, show the drag position instead of the playback position.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val position = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TimeLabel(if (dragging != null) (position * duration).toLong() else state.positionMs)
        Slider(
            value = position,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seek((it * duration).toLong()) }
                dragging = null
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        TimeLabel(state.durationMs)
    }
}

@Composable
private fun TimeLabel(ms: Long) =
    Text(formatDuration(ms), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
