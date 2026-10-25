package com.metrolist.desktop.auth

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** Uses shell scripts as stand-in browsers that hold the profile lock like Chromium does. */
class BrowserLoginTest {
    @Test
    fun openWindowWaitsUntilTheBrowserCloses() = assertWaits(
        """
        ln -s "host-${'$'}${'$'}" "${'$'}PROFILE/SingletonLock"; sleep 2; rm "${'$'}PROFILE/SingletonLock"
        """,
    )

    @Test
    fun openWindowFollowsALauncherThatHandsOff() = assertWaits(
        // Exits right away while a "browser" process keeps the profile locked for a while.
        """
        sh -c 'sleep 1; ln -s "host-${'$'}${'$'}" "${'$'}0/SingletonLock"; sleep 2' "${'$'}PROFILE" &
        """,
    )

    @Test
    fun openWindowIgnoresALockLeftByAKilledBrowser() = assertWaits(
        // The lock outlives the "browser", like Chromium's after it was killed.
        """
        ln -s "host-${'$'}${'$'}" "${'$'}PROFILE/SingletonLock"; sleep 2
        """,
    )

    private fun assertWaits(body: String) {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val dir = Files.createTempDirectory("meld-login").toFile()
        try {
            val profile = File(dir, "profile")
            val browser =
                File(dir, "browser.sh").apply {
                    writeText("#!/bin/sh\nPROFILE=\"${profile.absolutePath}\"\n${body.trimIndent()}\n")
                    setExecutable(true)
                }
            val start = System.currentTimeMillis()
            runBlocking { BrowserLogin.openWindow(browser, profile, "about:blank").getOrThrow() }
            val elapsed = System.currentTimeMillis() - start
            assertTrue(elapsed >= 2000, "returned after ${elapsed}ms, before the browser closed")
            assertTrue(elapsed < 10_000, "took ${elapsed}ms to notice the browser closed")
        } finally {
            dir.deleteRecursively()
        }
    }
}
