package com.metrolist.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

@Serializable
enum class AudioQualitySetting { AUTO, HIGH, LOW }

@Serializable
data class Settings(
    val volume: Int = 80,
    val audioQuality: AudioQualitySetting = AudioQualitySetting.AUTO,
    val darkTheme: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val sponsorBlockCategories: Set<String> = setOf("music_offtopic", "sponsor", "selfpromo"),
    val autoplayRadio: Boolean = true,
    val visitorData: String? = null,
)

/** Settings saved as JSON in the app data folder (%APPDATA%\Meld on Windows). */
object SettingsStore {
    private val file = AppDirs.file("settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val state =
        MutableStateFlow(runCatching { json.decodeFromString<Settings>(file.readText()) }.getOrDefault(Settings()))
    val settings: StateFlow<Settings> = state.asStateFlow()

    val current: Settings get() = state.value

    fun update(transform: (Settings) -> Settings) {
        val updated = transform(state.value)
        if (updated == state.value) return
        state.value = updated
        runCatching { file.writeText(json.encodeToString(updated)) }
            .onFailure { Timber.w(it, "Could not save settings") }
    }
}
