package com.metrolist.desktop

import com.metrolist.desktop.youtube.YouTubeAccount
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import timber.log.Timber
import java.util.Locale

object YouTubeSession {
    /**
     * Mirrors the Android app's startup: system locale for content, a persisted visitor id, and the
     * signed-in account's session when there is one.
     */
    suspend fun initialize() {
        val locale = Locale.getDefault()
        YouTube.locale =
            YouTubeLocale(
                gl = locale.country.takeIf { it.length == 2 } ?: "US",
                hl = locale.language.takeIf { it.isNotBlank() } ?: "en",
            )
        YouTube.visitorData =
            SettingsStore.current.visitorData
                ?: YouTube.visitorData()
                    .onFailure { Timber.w(it, "Could not fetch visitor data") }
                    .getOrNull()
                    ?.also { data -> SettingsStore.update { it.copy(visitorData = data) } }
        YouTubeAccount.initialize()
    }
}
