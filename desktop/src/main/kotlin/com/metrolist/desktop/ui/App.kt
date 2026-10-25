package com.metrolist.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.playback.PlayerController

@Composable
fun MeldApp(player: PlayerController) {
    val nav = remember { Navigator(player) }
    val state by player.state.collectAsState()
    var queueOpen by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                NavigationRail(Modifier.fillMaxHeight()) {
                    IconButton(onClick = nav::back, enabled = nav.canGoBack, modifier = Modifier.padding(vertical = 8.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    RailItem(nav, Screen.Home, "Home") { Icon(Icons.Default.Home, null) }
                    RailItem(nav, Screen.Search, "Search") { Icon(Icons.Default.Search, null) }
                    RailItem(nav, Screen.Library, "Library") { Icon(Icons.Default.LibraryMusic, null) }
                    RailItem(nav, Screen.Settings, "Settings") { Icon(Icons.Default.Settings, null) }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (val screen = nav.current) {
                        Screen.Home -> HomeScreen(nav)
                        Screen.Search -> SearchScreen(nav)
                        Screen.Settings -> SettingsScreen()
                        is Screen.Collection -> CollectionScreen(nav, screen)
                        is Screen.Artist -> ArtistScreen(nav, screen.id)
                        Screen.Library -> LibraryScreen(nav)
                        is Screen.SpotifyCollection -> SpotifyCollectionScreen(nav, screen)
                        is Screen.ArtistByName -> ArtistByNameScreen(nav, screen.name)
                    }
                }
                if (queueOpen && state.current != null) QueuePanel(state, player, nav.songActions)
            }
            PlayerBar(
                state = state,
                player = player,
                queueOpen = queueOpen,
                onToggleQueue = { queueOpen = !queueOpen },
                onOpenArtist = { nav.push(Screen.Artist(it)) },
            )
        }
    }
}

@Composable
private fun RailItem(nav: Navigator, tab: Screen, label: String, icon: @Composable () -> Unit) {
    NavigationRailItem(
        selected = nav.current == tab,
        onClick = { nav.selectTab(tab) },
        icon = icon,
        label = { Text(label) },
    )
}
