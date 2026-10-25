package com.metrolist.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.playback.PlayerController
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Collection(val id: String, val isAlbum: Boolean) : Screen
    data class Artist(val id: String) : Screen
}

/** Back stack per top-level tab, plus the actions every screen shares. */
class Navigator(val player: PlayerController) {
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    var lastQuery by mutableStateOf("")

    val songActions =
        SongActions(
            playNext = player::playNext,
            addToQueue = player::addToQueue,
            startRadio = player::playRadio,
            openArtist = { push(Screen.Artist(it)) },
            openAlbum = { push(Screen.Collection(it, isAlbum = true)) },
        )

    fun selectTab(tab: Screen) {
        stack.clear()
        stack.add(tab)
    }

    fun push(screen: Screen) {
        if (stack.last() != screen) stack.add(screen)
    }

    fun back() {
        if (canGoBack) stack.removeAt(stack.lastIndex)
    }

    fun openItem(item: YTItem) {
        when (item) {
            is SongItem -> player.playRadio(item)
            is AlbumItem -> push(Screen.Collection(item.browseId, isAlbum = true))
            is PlaylistItem -> push(Screen.Collection(item.id, isAlbum = false))
            is PodcastItem -> push(Screen.Collection(item.id, isAlbum = false))
            is ArtistItem -> push(Screen.Artist(item.id))
            else -> Unit
        }
    }
}
