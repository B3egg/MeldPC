package com.metrolist.desktop.playback

import com.sun.jna.NativeLibrary
import timber.log.Timber
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File

/** Thin wrapper around libVLC, which decodes the Opus/AAC audio YouTube serves. */
class VlcAudioEngine private constructor(
    private val factory: MediaPlayerFactory,
) {
    interface Listener {
        fun onPlaying()
        fun onPaused()
        fun onFinished()
        fun onError()
        fun onTime(positionMs: Long)
        fun onLength(durationMs: Long)
    }

    private val player: MediaPlayer = factory.mediaPlayers().newMediaPlayer()

    fun setListener(listener: Listener) {
        player.events().addMediaPlayerEventListener(
            object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) = listener.onPlaying()
                override fun paused(mediaPlayer: MediaPlayer) = listener.onPaused()
                override fun finished(mediaPlayer: MediaPlayer) = listener.onFinished()
                override fun error(mediaPlayer: MediaPlayer) = listener.onError()
                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = listener.onTime(newTime)
                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = listener.onLength(newLength)
            },
        )
    }

    // libVLC must not be called back into from its own event threads, so every command is submitted.
    fun play(url: String, startMs: Long = 0) =
        player.submit {
            val options = if (startMs > 0) arrayOf(":start-time=${startMs / 1000.0}") else emptyArray()
            player.media().play(url, *options)
        }

    fun pause() = player.submit { player.controls().setPause(true) }

    fun resume() = player.submit { player.controls().setPause(false) }

    fun stop() = player.submit { player.controls().stop() }

    fun seek(positionMs: Long) = player.submit { player.controls().setTime(positionMs) }

    fun setVolume(volume: Int) = player.submit { player.audio().setVolume(volume.coerceIn(0, 100)) }

    fun release() {
        player.release()
        factory.release()
    }

    companion object {
        /**
         * Loads libVLC, preferring the copy bundled with the installer (in the app's resources
         * folder) and falling back to a system-wide VLC installation. Returns null if neither exists.
         */
        fun create(): VlcAudioEngine? {
            bundledVlcDir()?.let { dir ->
                Timber.i("Using bundled VLC at $dir")
                NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), dir.absolutePath)
                NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), dir.absolutePath)
            } ?: run {
                if (!NativeDiscovery().discover()) return null
            }
            return runCatching {
                VlcAudioEngine(MediaPlayerFactory("--no-video", "--quiet", "--intf=dummy", "--network-caching=1500"))
            }.onFailure { Timber.e(it, "Could not start VLC") }.getOrNull()
        }

        private fun bundledVlcDir(): File? =
            System.getProperty("compose.application.resources.dir")
                ?.let { File(it, "vlc") }
                ?.takeIf { File(it, "libvlc.dll").exists() || File(it, "libvlc.so").exists() }
    }
}
