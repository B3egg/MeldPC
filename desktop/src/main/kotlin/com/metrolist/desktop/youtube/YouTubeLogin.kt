package com.metrolist.desktop.youtube

import com.metrolist.desktop.AppDirs
import com.metrolist.desktop.auth.BrowserLogin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** What InnerTube needs to act as the signed-in user, like the Android app's LoginScreen collects. */
data class YouTubeAuth(
    val cookie: String,
    val visitorData: String?,
    val dataSyncId: String?,
    val authUser: String,
)

/** Signs in to Google in a browser window, then reads the YouTube Music session from it. */
object YouTubeLogin {
    const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    private const val MUSIC_URL = "https://music.youtube.com"

    // Same values the Android app reads through its JavaScript interface.
    private const val PAGE_CONFIG_SCRIPT =
        "JSON.stringify(window.yt && window.yt.config_ ? {" +
            "v: window.yt.config_.VISITOR_DATA || null, " +
            "d: window.yt.config_.DATASYNC_ID || null, " +
            "a: String(window.yt.config_.SESSION_INDEX || 0)} : null)"

    val profileDir: File get() = AppDirs.file("youtube-login")

    suspend fun login(): Result<YouTubeAuth> =
        runCatching { BrowserLogin.requireBrowser() }.fold({ login(it, LOGIN_URL) }, { Result.failure(it) })

    internal suspend fun login(
        browser: File,
        url: String,
        extraArgs: List<String> = emptyList(),
        musicUrl: String = MUSIC_URL,
        cookieDomain: String = "youtube.com",
        timeoutMs: Long = 10 * 60 * 1000L,
    ): Result<YouTubeAuth> =
        BrowserLogin.run(browser, profileDir, url, extraArgs, timeoutMs) { cdp ->
            val cookies = cdp.cookies(cookieDomain)
            if (cookies["SAPISID"].isNullOrBlank()) return@run null
            val raw = cdp.evaluate(musicUrl, PAGE_CONFIG_SCRIPT)?.jsonPrimitive?.contentOrNull ?: return@run null
            val config = Json.parseToJsonElement(raw).jsonObject
            val visitorData = config["v"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@run null
            YouTubeAuth(
                cookie = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" },
                visitorData = visitorData,
                dataSyncId = config["d"]?.jsonPrimitive?.contentOrNull?.substringBefore("||")?.takeIf { it.isNotBlank() },
                authUser = config["a"]?.jsonPrimitive?.contentOrNull.orEmpty().filter(Char::isDigit).ifBlank { "0" },
            )
        }

    /** Removes the login window's browser profile, which also signs it out of Google. */
    fun clearProfile() {
        profileDir.deleteRecursively()
    }
}
