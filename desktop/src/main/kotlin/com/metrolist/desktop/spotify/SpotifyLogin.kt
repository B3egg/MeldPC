package com.metrolist.desktop.spotify

import com.metrolist.desktop.AppDirs
import com.metrolist.desktop.auth.BrowserLogin
import com.metrolist.spotify.SpotifyAuth
import java.io.File

data class SpotifyCookies(val spDc: String, val spKey: String)

/** Signs in to Spotify in a browser window and reads the sp_dc / sp_key session cookies. */
object SpotifyLogin {
    val profileDir: File get() = AppDirs.file("spotify-login")

    /** Opens the login window and suspends until the user has signed in or closed the window. */
    suspend fun login(): Result<SpotifyCookies> =
        runCatching { BrowserLogin.requireBrowser() }.fold({ login(it, SpotifyAuth.LOGIN_URL) }, { Result.failure(it) })

    internal suspend fun login(
        browser: File,
        url: String,
        extraArgs: List<String> = emptyList(),
    ): Result<SpotifyCookies> =
        BrowserLogin.run(browser, profileDir, url, extraArgs) { cdp ->
            val all = cdp.cookies("spotify.com")
            all["sp_dc"]?.takeIf { it.isNotBlank() }?.let { SpotifyCookies(it, all["sp_key"].orEmpty()) }
        }

    /** Removes the login window's browser profile, which also signs it out of Spotify. */
    fun clearProfile() {
        profileDir.deleteRecursively()
    }
}
