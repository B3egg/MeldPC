package com.metrolist.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.AudioQualitySetting
import com.metrolist.desktop.Settings
import com.metrolist.desktop.spotify.SpotifySession
import com.metrolist.desktop.SettingsStore
import com.metrolist.desktop.playback.SponsorBlock

private val CategoryLabels =
    mapOf(
        "music_offtopic" to "Non-music parts of music videos",
        "sponsor" to "Sponsors",
        "selfpromo" to "Self-promotion",
        "interaction" to "Subscribe and like reminders",
        "intro" to "Intros",
        "outro" to "Outros and end cards",
        "preview" to "Previews and recaps",
        "filler" to "Filler and jokes",
    )

@Composable
fun SettingsScreen() {
    val settings by SettingsStore.settings.collectAsState()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Heading("Appearance")
        ToggleRow("Dark theme", settings.darkTheme) { v -> SettingsStore.update { it.copy(darkTheme = v) } }

        HorizontalDivider()
        SpotifySection(settings)

        HorizontalDivider()
        Heading("Playback")
        Text("Audio quality")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioQualitySetting.entries.forEach { q ->
                FilterChip(
                    selected = settings.audioQuality == q,
                    onClick = { SettingsStore.update { it.copy(audioQuality = q) } },
                    label = { Text(q.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                )
            }
        }
        ToggleRow("Keep playing similar songs when the queue ends", settings.autoplayRadio) { v ->
            SettingsStore.update { it.copy(autoplayRadio = v) }
        }

        HorizontalDivider()
        Heading("SponsorBlock")
        ToggleRow("Skip sponsor segments and non-music parts", settings.sponsorBlockEnabled) { v ->
            SettingsStore.update { it.copy(sponsorBlockEnabled = v) }
        }
        if (settings.sponsorBlockEnabled) {
            SponsorBlock.ALL_CATEGORIES.forEach { category ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = category in settings.sponsorBlockCategories,
                        onCheckedChange = { checked ->
                            SettingsStore.update {
                                it.copy(
                                    sponsorBlockCategories =
                                        if (checked) it.sponsorBlockCategories + category else it.sponsorBlockCategories - category,
                                )
                            }
                        },
                    )
                    Text(CategoryLabels[category] ?: category)
                }
            }
        }

        HorizontalDivider()
        Text(
            "Meld for Windows. Ad-free YouTube Music streaming, based on Meld and Metrolist (GPL-3.0).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpotifySection(settings: Settings) {
    val state by SpotifySession.state.collectAsState()
    val error by SpotifySession.lastError.collectAsState()
    var showCookie by remember { mutableStateOf(false) }
    var cookie by remember { mutableStateOf("") }

    Heading("Spotify")
    when (val s = state) {
        SpotifySession.State.LoggedOut -> {
            Text(
                "Sign in to get Home, search and radio based on your Spotify listening. A free account works; " +
                    "music still streams from YouTube Music.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = SpotifySession::login) { Text("Sign in with Spotify") }
                TextButton(onClick = { showCookie = !showCookie }) { Text("Use a cookie instead") }
            }
            if (showCookie) {
                Text(
                    "Copy the value of the sp_dc cookie for open.spotify.com from your browser while signed in, and paste it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(cookie, { cookie = it }, label = { Text("sp_dc") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { SpotifySession.loginWithCookie(cookie) }, enabled = cookie.isNotBlank()) { Text("Sign in") }
                }
            }
        }
        SpotifySession.State.LoggingIn ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Finish signing in in the browser window that opened. It closes by itself when you're done.")
            }
        is SpotifySession.State.LoggedIn -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Signed in as ${s.name ?: "Spotify user"}", Modifier.weight(1f))
                OutlinedButton(onClick = SpotifySession::logout) { Text("Sign out") }
            }
            ToggleRow("Show Spotify picks on Home", settings.spotifyHome) { v -> SettingsStore.update { it.copy(spotifyHome = v) } }
            ToggleRow("Search songs with Spotify", settings.spotifySearch) { v -> SettingsStore.update { it.copy(spotifySearch = v) } }
            ToggleRow("Build radio from your Spotify taste", settings.spotifyRadio) { v -> SettingsStore.update { it.copy(spotifyRadio = v) } }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
