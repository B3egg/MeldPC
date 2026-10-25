package com.metrolist.desktop.spotify

import com.metrolist.desktop.AppDirs
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyAuth
import com.metrolist.spotify.SpotifyHashProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.URI

/**
 * Keeps the Spotify session alive: stores the sp_dc cookie, turns it into short-lived web-player
 * tokens (like the Android app's SpotifyTokenManager), and keeps the GraphQL hashes fresh.
 */
object SpotifySession {
    private const val HASHES_URL = "https://francescograzioso.github.io/Meld/spotify-gql-hashes.json"

    @Serializable
    private data class Stored(
        val spDc: String = "",
        val spKey: String = "",
        val accessToken: String = "",
        val expiresAtMs: Long = 0,
        val displayName: String? = null,
        val hashesJson: String? = null,
    )

    sealed interface State {
        data object LoggedOut : State
        data object LoggingIn : State
        data class LoggedIn(val name: String?) : State
    }

    private val file = AppDirs.file("spotify.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    @Volatile
    private var stored: Stored = runCatching { json.decodeFromString<Stored>(file.readText()) }.getOrDefault(Stored())

    private val _state = MutableStateFlow(if (stored.spDc.isNotEmpty()) State.LoggedIn(stored.displayName) else State.LoggedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    val isLoggedIn: Boolean get() = _state.value is State.LoggedIn

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun initialize() {
        Spotify.logger = { level, message ->
            when (level) {
                "E" -> Timber.tag("SpotifyAPI").e(message)
                "W" -> Timber.tag("SpotifyAPI").w(message)
                else -> Timber.tag("SpotifyAPI").d(message)
            }
        }
        stored.hashesJson?.let(::applyHashes)
        Spotify.onHashExpired = { scope.launch { syncHashes() } }
        scope.launch {
            syncHashes()
            if (isLoggedIn) ensureAuthenticated()
        }
    }

    fun login() {
        if (_state.value == State.LoggingIn) return
        _state.value = State.LoggingIn
        _lastError.value = null
        scope.launch {
            SpotifyLogin.login()
                .onSuccess { cookies -> completeLogin(cookies.spDc, cookies.spKey) }
                .onFailure {
                    _lastError.value = it.message
                    _state.value = State.LoggedOut
                }
        }
    }

    /** For people without Edge or Chrome: sign in with an sp_dc cookie copied from a browser. */
    fun loginWithCookie(spDc: String) {
        _state.value = State.LoggingIn
        _lastError.value = null
        scope.launch { completeLogin(spDc.trim(), "") }
    }

    private suspend fun completeLogin(spDc: String, spKey: String) {
        SpotifyAuth.fetchAccessToken(spDc, spKey)
            .onSuccess { token ->
                Spotify.accessToken = token.accessToken
                val name = Spotify.me().getOrNull()?.displayName
                update { Stored(spDc, spKey, token.accessToken, token.accessTokenExpirationTimestampMs, name, it.hashesJson) }
                _state.value = State.LoggedIn(name)
            }.onFailure {
                Timber.w(it, "Spotify token request failed")
                _lastError.value = "Spotify sign-in failed: ${it.message}"
                _state.value = State.LoggedOut
            }
    }

    fun logout() {
        Spotify.accessToken = null
        update { Stored(hashesJson = it.hashesJson) }
        SpotifyLogin.clearProfile()
        SpotifyProfile.clear()
        _state.value = State.LoggedOut
    }

    /** Makes sure [Spotify.accessToken] holds a valid token; returns false when signed out. */
    suspend fun ensureAuthenticated(): Boolean {
        if (!isLoggedIn) return false
        val now = System.currentTimeMillis()
        if (stored.accessToken.isNotEmpty() && now < stored.expiresAtMs - 60_000) {
            Spotify.accessToken = stored.accessToken
            return true
        }
        return refreshMutex.withLock {
            if (stored.accessToken.isNotEmpty() && System.currentTimeMillis() < stored.expiresAtMs - 60_000) {
                Spotify.accessToken = stored.accessToken
                return@withLock true
            }
            SpotifyAuth.fetchAccessToken(stored.spDc, stored.spKey).fold(
                onSuccess = { token ->
                    Spotify.accessToken = token.accessToken
                    update { it.copy(accessToken = token.accessToken, expiresAtMs = token.accessTokenExpirationTimestampMs) }
                    true
                },
                onFailure = { e ->
                    Timber.w(e, "Spotify token refresh failed")
                    if (e.message?.contains("anonymous") == true || e.message?.contains("expired") == true) {
                        _lastError.value = "Your Spotify session expired. Please sign in again."
                        logout()
                    }
                    false
                },
            )
        }
    }

    private suspend fun syncHashes() {
        runCatching { URI(HASHES_URL).toURL().readText() }
            .onSuccess { body ->
                if (applyHashes(body)) update { it.copy(hashesJson = body) }
            }.onFailure { Timber.w(it, "Could not fetch Spotify hashes") }
    }

    private fun applyHashes(body: String): Boolean {
        val operations =
            runCatching { json.parseToJsonElement(body).jsonObject["operations"]?.jsonObject }.getOrNull() ?: return false
        val entries =
            operations.mapNotNull { (op, value) ->
                val obj = value.jsonObject
                val hash = obj["hash"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                op to SpotifyHashProvider.RemoteHashEntry(hash, obj["previous_hash"]?.jsonPrimitive?.contentOrNull)
            }.toMap()
        if (entries.isEmpty()) return false
        SpotifyHashProvider.updateHashes(entries, SpotifyHashProvider.HashSource.REMOTE)
        return true
    }

    private fun update(transform: (Stored) -> Stored) {
        synchronized(this) {
            stored = transform(stored)
            runCatching { file.writeText(json.encodeToString(stored)) }
                .onFailure { Timber.w(it, "Could not save Spotify session") }
        }
    }
}
