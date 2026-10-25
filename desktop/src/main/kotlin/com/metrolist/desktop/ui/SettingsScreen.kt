package com.metrolist.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.AudioQualitySetting
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
private fun Heading(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
