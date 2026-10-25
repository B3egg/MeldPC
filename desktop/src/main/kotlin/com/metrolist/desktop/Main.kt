package com.metrolist.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.metrolist.desktop.playback.PlayerController
import com.metrolist.desktop.playback.VlcAudioEngine
import com.metrolist.desktop.ui.LoadingBox
import com.metrolist.desktop.ui.MeldApp
import com.metrolist.desktop.ui.MeldTheme
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.net.URI

fun main() {
    val player = VlcAudioEngine.create()?.let(::PlayerController)
    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("meld.png")?.use {
        BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
    }

    application {
        Window(
            onCloseRequest = {
                player?.release()
                exitApplication()
            },
            title = "Meld",
            icon = icon,
            state = rememberWindowState(width = 1280.dp, height = 820.dp),
        ) {
            val settings by SettingsStore.settings.collectAsState()
            MeldTheme(dark = settings.darkTheme) {
                if (player == null) {
                    VlcMissing()
                } else {
                    var ready by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        YouTubeSession.initialize()
                        ready = true
                    }
                    if (ready) MeldApp(player) else Surface { LoadingBox() }
                }
            }
        }
    }
}

@Composable
private fun VlcMissing() {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Meld needs VLC to play audio", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "VLC wasn't found on this computer. Install the 64-bit version of VLC media player, then start Meld again.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { runCatching { Desktop.getDesktop().browse(URI("https://www.videolan.org/vlc/")) } }) {
                Text("Download VLC")
            }
        }
    }
}
