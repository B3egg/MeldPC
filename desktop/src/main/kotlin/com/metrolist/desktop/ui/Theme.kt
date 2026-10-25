package com.metrolist.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF8AB4F8)

private val DarkColors =
    darkColorScheme(
        primary = Accent,
        onPrimary = Color(0xFF0B1B33),
        secondaryContainer = Color(0xFF2A3446),
        background = Color(0xFF101114),
        surface = Color(0xFF101114),
        surfaceContainer = Color(0xFF181A1F),
        surfaceContainerHigh = Color(0xFF20232A),
    )

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF2D5DA8),
        secondaryContainer = Color(0xFFDCE6F7),
    )

@Composable
fun MeldTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
