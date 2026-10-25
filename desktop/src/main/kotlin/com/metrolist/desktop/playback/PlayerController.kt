package com.metrolist.desktop.playback

import com.metrolist.desktop.AudioQualitySetting
import com.metrolist.desktop.SettingsStore
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertubex.extraction.AudioQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerState(
    val queue: List<SongItem> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 80,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val error: String? = null,
    val skippedSegment: String? = null,
) {
    val current: SongItem? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = index in 0 until queue.lastIndex
    val hasPrevious: Boolean get() = index > 0
}

/** Owns the queue and drives VLC; the UI only reads [state] and calls the public functions. */
class PlayerController(
    private val engine: VlcAudioEngine,
) : VlcAudioEngine.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val streams = ConcurrentHashMap<String, ResolvedStream>()
    private val resolveLocks = ConcurrentHashMap<String, Mutex>()
    private val proxy = StreamProxy(::streamFor)

    private val _state = MutableStateFlow(PlayerState(volume = SettingsStore.current.volume))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var radioJob: Job? = null
    private var radioContinuation: Pair<WatchEndpoint, String>? = null
    private var segments: Pair<String, List<SponsorBlock.Segment>>? = null
    private var consecutiveErrors = 0

    init {
        engine.setListener(this)
        engine.setVolume(_state.value.volume)
    }

    // region Queue commands

    fun playQueue(songs: List<SongItem>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        radioContinuation = null
        _state.update { it.copy(queue = songs, index = startIndex.coerceIn(songs.indices)) }
        load()
    }

    /** Plays [song] followed by a YouTube Music radio built from it, like tapping a song in the Android app. */
    fun playRadio(song: SongItem) {
        playQueue(listOf(song))
        extendRadio(WatchEndpoint(videoId = song.id, playlistId = "RDAMVM${song.id}"), continuation = null)
    }

    /** Plays a YouTube Music watch endpoint (e.g. an artist's radio) and keeps extending it. */
    fun playEndpoint(endpoint: WatchEndpoint) {
        radioJob?.cancel()
        radioJob =
            scope.launch {
                val result = YouTube.next(endpoint).getOrElse {
                    _state.update { s -> s.copy(error = it.message ?: "Could not start radio") }
                    return@launch
                }
                if (result.items.isEmpty()) return@launch
                playQueue(result.items, result.currentIndex ?: 0)
                radioContinuation = result.continuation?.let { result.endpoint to it }
            }
    }

    fun playNext(song: SongItem) {
        if (_state.value.index < 0) return playQueue(listOf(song))
        _state.update { s -> s.copy(queue = s.queue.toMutableList().apply { add(s.index + 1, song) }) }
    }

    fun addToQueue(song: SongItem) {
        if (_state.value.index < 0) return playQueue(listOf(song))
        _state.update { s -> s.copy(queue = s.queue + song) }
    }

    fun removeAt(position: Int) {
        val s = _state.value
        if (position !in s.queue.indices) return
        if (position == s.index) {
            val queue = s.queue.toMutableList().apply { removeAt(position) }
            if (queue.isEmpty()) {
                engine.stop()
                _state.value = PlayerState(volume = s.volume, repeatMode = s.repeatMode)
                return
            }
            _state.update { it.copy(queue = queue, index = position.coerceAtMost(queue.lastIndex)) }
            load()
        } else {
            _state.update { it.copy(queue = it.queue.filterIndexed { i, _ -> i != position }, index = if (position < it.index) it.index - 1 else it.index) }
        }
    }

    fun move(from: Int, to: Int) {
        val size = _state.value.queue.size
        if (from !in 0 until size || to !in 0 until size) return
        _state.update { s ->
            val queue = s.queue.toMutableList().apply { add(to, removeAt(from)) }
            val index =
                when {
                    s.index == from -> to
                    from < s.index && to >= s.index -> s.index - 1
                    from > s.index && to <= s.index -> s.index + 1
                    else -> s.index
                }
            s.copy(queue = queue, index = index)
        }
    }

    fun jumpTo(position: Int) {
        if (position !in _state.value.queue.indices) return
        _state.update { it.copy(index = position) }
        load()
    }

    fun shuffleUpcoming() =
        _state.update { s -> s.copy(queue = s.queue.take(s.index + 1) + s.queue.drop(s.index + 1).shuffled()) }

    // endregion

    // region Transport commands

    fun togglePlay() {
        val s = _state.value
        when {
            s.current == null -> return
            s.error != null -> load()
            s.isPlaying -> engine.pause()
            else -> engine.resume()
        }
    }

    fun next() {
        val s = _state.value
        when {
            s.hasNext -> jumpTo(s.index + 1)
            s.repeatMode == RepeatMode.ALL && s.queue.isNotEmpty() -> jumpTo(0)
        }
    }

    fun previous() {
        val s = _state.value
        if (s.positionMs > 3000 || !s.hasPrevious) seek(0) else jumpTo(s.index - 1)
    }

    fun seek(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
        engine.seek(positionMs)
    }

    fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 100)
        _state.update { it.copy(volume = v) }
        engine.setVolume(v)
        SettingsStore.update { it.copy(volume = v) }
    }

    fun cycleRepeat() =
        _state.update { it.copy(repeatMode = RepeatMode.entries[(it.repeatMode.ordinal + 1) % RepeatMode.entries.size]) }

    fun release() {
        scope.cancel()
        proxy.stop()
        engine.release()
    }

    // endregion

    private fun load() {
        val song = _state.value.current ?: return
        loadJob?.cancel()
        engine.stop()
        _state.update { it.copy(isLoading = true, isPlaying = false, positionMs = 0, durationMs = (song.duration ?: 0) * 1000L, error = null) }
        loadJob =
            scope.launch {
                // Resolve before handing VLC the proxy URL, so failures show up as a readable error.
                runCatching { streamFor(song.id, forceRefresh = false) }
                    .onFailure { e ->
                        Timber.e(e, "Could not resolve ${song.id}")
                        onLoadFailed(e.message ?: "Could not load this song")
                        return@launch
                    }
                if (_state.value.current?.id != song.id) return@launch
                engine.play(proxy.urlFor(song.id))
                loadSegments(song.id)
                maybeExtendRadio()
                prefetchNext()
            }
    }

    private suspend fun streamFor(videoId: String, forceRefresh: Boolean): ResolvedStream =
        resolveLocks.getOrPut(videoId) { Mutex() }.withLock {
            val cached = streams[videoId]
            if (!forceRefresh && cached != null && cached.expiresAtMs - System.currentTimeMillis() > 30_000) {
                return cached
            }
            StreamResolver.audioQuality =
                when (SettingsStore.current.audioQuality) {
                    AudioQualitySetting.AUTO -> AudioQuality.AUTO
                    AudioQualitySetting.HIGH -> AudioQuality.HIGH
                    AudioQualitySetting.LOW -> AudioQuality.LOW
                }
            StreamResolver.resolve(videoId).getOrThrow().also { streams[videoId] = it }
        }

    private fun prefetchNext() {
        val upcoming = _state.value.queue.getOrNull(_state.value.index + 1) ?: return
        scope.launch { runCatching { streamFor(upcoming.id, forceRefresh = false) } }
    }

    private fun onLoadFailed(message: String) {
        _state.update { it.copy(isLoading = false, isPlaying = false, error = message) }
        consecutiveErrors++
        if (consecutiveErrors < 3 && _state.value.hasNext) {
            scope.launch {
                delay(1500)
                next()
            }
        }
    }

    private fun loadSegments(videoId: String) {
        segments = null
        val settings = SettingsStore.current
        if (!settings.sponsorBlockEnabled) return
        scope.launch {
            val result = SponsorBlock.segments(videoId, settings.sponsorBlockCategories)
            if (_state.value.current?.id == videoId) segments = videoId to result
        }
    }

    private fun maybeExtendRadio() {
        val s = _state.value
        val continuation = radioContinuation
        if (s.queue.size - s.index > 3) return
        if (continuation != null) {
            extendRadio(continuation.first, continuation.second)
        } else if (SettingsStore.current.autoplayRadio && radioJob?.isActive != true) {
            val seed = s.current ?: return
            extendRadio(WatchEndpoint(videoId = seed.id, playlistId = "RDAMVM${seed.id}"), continuation = null)
        }
    }

    private fun extendRadio(endpoint: WatchEndpoint, continuation: String?) {
        if (radioJob?.isActive == true) return
        radioJob =
            scope.launch {
                val result = YouTube.next(endpoint, continuation).getOrElse {
                    Timber.w(it, "Radio request failed")
                    return@launch
                }
                radioContinuation = result.continuation?.let { result.endpoint to it }
                _state.update { s ->
                    val known = s.queue.mapTo(HashSet()) { it.id }
                    s.copy(queue = s.queue + result.items.filter { it.id !in known })
                }
                prefetchNext()
            }
    }

    // region VLC events (called on VLC's threads)

    override fun onPlaying() {
        consecutiveErrors = 0
        _state.update { it.copy(isPlaying = true, isLoading = false, error = null) }
    }

    override fun onPaused() = _state.update { it.copy(isPlaying = false) }

    override fun onFinished() {
        scope.launch {
            val s = _state.value
            when {
                s.repeatMode == RepeatMode.ONE -> load()
                s.hasNext || s.repeatMode == RepeatMode.ALL -> next()
                else -> _state.update { it.copy(isPlaying = false, positionMs = 0) }
            }
        }
    }

    override fun onError() {
        val id = _state.value.current?.id ?: return
        streams.remove(id)
        onLoadFailed("Playback failed")
    }

    override fun onTime(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
        val (videoId, list) = segments ?: return
        if (videoId != _state.value.current?.id) return
        list.firstOrNull { positionMs in it.startMs..(it.endMs - 200) }?.let { segment ->
            Timber.d("SponsorBlock: skipping ${segment.category}")
            engine.seek(segment.endMs)
            _state.update { it.copy(skippedSegment = segment.category) }
            scope.launch {
                delay(3000)
                _state.update { if (it.skippedSegment == segment.category) it.copy(skippedSegment = null) else it }
            }
        }
    }

    override fun onLength(durationMs: Long) {
        if (durationMs > 0) _state.update { it.copy(durationMs = durationMs) }
    }

    // endregion
}
