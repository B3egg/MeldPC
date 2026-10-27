package com.metrolist.desktop.youtube

import com.metrolist.desktop.AppDirs
import com.metrolist.desktop.SettingsStore
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.completed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * The signed-in YouTube Music account: stores the session the login window collected, applies it to
 * InnerTube on start, and keeps the set of liked songs so the player can show the like state.
 */
object YouTubeAccount {
    @Serializable
    private data class Stored(
        val cookie: String = "",
        val visitorData: String? = null,
        val dataSyncId: String? = null,
        val authUser: String = "0",
        val name: String? = null,
        val email: String? = null,
        val thumbnailUrl: String? = null,
    )

    sealed interface State {
        data object LoggedOut : State
        data object LoggingIn : State
        data class LoggedIn(val name: String?, val email: String?, val thumbnailUrl: String?) : State
    }

    private val file = AppDirs.file("youtube-account.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var stored: Stored = runCatching { json.decodeFromString<Stored>(file.readText()) }.getOrDefault(Stored())

    private val _state = MutableStateFlow(stored.toState())
    val state: StateFlow<State> = _state.asStateFlow()

    val isLoggedIn: Boolean get() = _state.value is State.LoggedIn

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    /** Applies the saved session, if any. Returns true when signed in. */
    fun initialize(): Boolean {
        if (stored.cookie.isEmpty()) return false
        apply(stored.cookie, stored.visitorData, stored.dataSyncId, stored.authUser)
        scope.launch { refreshLikes() }
        return true
    }

    fun login() {
        if (_state.value == State.LoggingIn) return
        _state.value = State.LoggingIn
        _lastError.value = null
        scope.launch {
            YouTubeLogin.login()
                .onSuccess { completeLogin(it) }
                .onFailure {
                    _lastError.value = it.message
                    _state.value = State.LoggedOut
                }
        }
    }

    /** For people without Edge or Chrome: sign in with the Cookie header copied from music.youtube.com. */
    fun loginWithCookie(cookie: String) {
        val trimmed = cookie.trim().removePrefix("Cookie:").trim()
        if ("SAPISID=" !in trimmed) {
            _lastError.value = "That cookie doesn't contain SAPISID. Copy the whole Cookie header from music.youtube.com while signed in."
            return
        }
        _state.value = State.LoggingIn
        _lastError.value = null
        scope.launch { completeLogin(YouTubeAuth(trimmed, visitorData = null, dataSyncId = null, authUser = "0")) }
    }

    private suspend fun completeLogin(auth: YouTubeAuth) {
        apply(auth.cookie, auth.visitorData, auth.dataSyncId, auth.authUser)
        val dataSyncId =
            auth.dataSyncId ?: YouTube.accountsList().getOrNull()
                ?.let { accounts -> accounts.firstOrNull { it.isSelected } ?: accounts.firstOrNull() }
                ?.dataSyncId?.substringBefore("||")
        YouTube.dataSyncId = dataSyncId
        YouTube.accountInfo()
            .onSuccess { info ->
                update {
                    Stored(auth.cookie, auth.visitorData, dataSyncId, auth.authUser, info.name, info.email, info.thumbnailUrl)
                }
                _state.value = stored.toState()
                refreshLikes()
            }.onFailure {
                Timber.w(it, "YouTube account check failed")
                clearSession()
                _lastError.value = "YouTube Music sign-in failed: ${it.message ?: "the account couldn't be read"}"
                _state.value = State.LoggedOut
            }
    }

    fun logout() {
        clearSession()
        update { Stored() }
        YouTubeLogin.clearProfile()
        _likedIds.value = emptySet()
        _state.value = State.LoggedOut
    }

    fun isLiked(videoId: String): Boolean = videoId in _likedIds.value

    /** Likes or unlikes [song] on the account, updating the local state right away. */
    fun toggleLike(song: SongItem) {
        if (!isLoggedIn) return
        val like = song.id !in _likedIds.value
        _likedIds.update { if (like) it + song.id else it - song.id }
        scope.launch {
            YouTube.likeVideo(song.id, like).onFailure { e ->
                Timber.w(e, "Could not ${if (like) "like" else "unlike"} ${song.id}")
                _likedIds.update { if (like) it - song.id else it + song.id }
                _lastError.value = "Couldn't update your likes on YouTube Music"
            }
        }
    }

    /** Adds a finished listen to the account's YouTube Music history. */
    fun registerPlayback(playbackUrl: String) {
        if (!isLoggedIn) return
        scope.launch {
            YouTube.registerPlayback(playbackTracking = playbackUrl)
                .onFailure { Timber.w(it, "Could not add the song to YouTube history") }
        }
    }

    suspend fun refreshLikes() {
        YouTube.playlist("LM").completed()
            .onSuccess { page -> _likedIds.value = page.songs.mapTo(HashSet()) { it.id } }
            .onFailure { Timber.w(it, "Could not load liked songs") }
    }

    private fun apply(cookie: String, visitorData: String?, dataSyncId: String?, authUser: String) {
        YouTube.cookie = cookie
        visitorData?.let { YouTube.visitorData = it }
        YouTube.dataSyncId = dataSyncId
        YouTube.authUser = authUser
        YouTube.useLoginForBrowse = true
    }

    private fun clearSession() {
        YouTube.cookie = null
        YouTube.dataSyncId = null
        YouTube.authUser = "0"
        YouTube.useLoginForBrowse = false
        SettingsStore.current.visitorData?.let { YouTube.visitorData = it }
    }

    private fun Stored.toState(): State =
        if (cookie.isEmpty()) State.LoggedOut else State.LoggedIn(name, email, thumbnailUrl)

    private fun update(transform: (Stored) -> Stored) {
        synchronized(this) {
            stored = transform(stored)
            runCatching { file.writeText(json.encodeToString(stored)) }
                .onFailure { Timber.w(it, "Could not save YouTube account") }
        }
    }
}
