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
    const val NOT_SIGNED_IN =
        "You weren't signed in yet. Sign in again, and close the window only after YouTube Music has opened."

    // Same values the Android app reads through its JavaScript interface.
    private const val PAGE_CONFIG_SCRIPT =
        "JSON.stringify(window.yt && window.yt.config_ ? {" +
            "v: window.yt.config_.VISITOR_DATA || null, " +
            "d: window.yt.config_.DATASYNC_ID || null, " +
            "a: String(window.yt.config_.SESSION_INDEX || 0)} : null)"

    val profileDir: File get() = AppDirs.file("youtube-login")

    /**
     * Signing in happens in a plain browser window that the user closes when YouTube Music has
     * opened, because Google refuses sign-in in windows controlled over DevTools. The session is
     * then read from the same browser profile in a second, off-screen window.
     */
    suspend fun login(): Result<YouTubeAuth> {
        val browser = runCatching { BrowserLogin.requireBrowser() }.getOrElse { return Result.failure(it) }
        BrowserLogin.openWindow(browser, profileDir, LOGIN_URL).onFailure { return Result.failure(it) }
        return readSession(browser, extraArgs = listOf("--window-position=-3000,-3000"))
    }

    internal suspend fun readSession(
        browser: File,
        musicUrl: String = MUSIC_URL,
        extraArgs: List<String> = emptyList(),
        cookieDomain: String = "youtube.com",
        timeoutMs: Long = 60_000,
    ): Result<YouTubeAuth> {
        var checks = 0
        return BrowserLogin.run(browser, profileDir, musicUrl, extraArgs, timeoutMs) { cdp ->
            val cookies = cdp.cookies(cookieDomain)
            if (cookies["SAPISID"].isNullOrBlank()) {
                if (++checks >= 3) throw BrowserLogin.LoginAbort(NOT_SIGNED_IN)
                return@run null
            }
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
    }

    /** Removes the login window's browser profile, which also signs it out of Google. */
    fun clearProfile() {
        profileDir.deleteRecursively()
    }
}
