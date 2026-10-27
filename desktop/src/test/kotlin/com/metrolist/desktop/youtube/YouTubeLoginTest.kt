package com.metrolist.desktop.youtube

import com.metrolist.desktop.auth.BrowserLogin
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives a real Chromium against fake sign-in and YouTube Music pages. Google's and YouTube's real
 * hostnames are HSTS-preloaded, so Chromium would force them onto HTTPS; the fake pages use test
 * hostnames instead. Runs only when MELD_TEST_BROWSER points at a Chromium-based browser.
 */
class YouTubeLoginTest {
    private val browser = System.getenv("MELD_TEST_BROWSER")?.let(::File)?.takeIf { it.canExecute() }
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val script =
                    if (ex.requestURI.path == "/seed") {
                        // Stands in for the user signing in to Google in the plain window.
                        """
                        document.cookie = "SAPISID=test-sapisid; domain=.meld.test; path=/; max-age=3600";
                        document.cookie = "SID=test-sid; domain=.meld.test; path=/; max-age=3600";
                        """
                    } else {
                        """window.yt = { config_: { VISITOR_DATA: "test-visitor", DATASYNC_ID: "sync-id||other", SESSION_INDEX: 1 } };"""
                    }
                val page = "<html><body>YouTube Music<script>$script</script></body></html>".toByteArray()
                ex.sendResponseHeaders(200, page.size.toLong())
                ex.responseBody.use { it.write(page) }
            }
            start()
        }
    private val port = server.address.port
    private val args = listOf("--headless=new", "--no-sandbox", "--host-resolver-rules=MAP *.meld.test 127.0.0.1")

    @AfterTest
    fun cleanUp() {
        server.stop(0)
        YouTubeLogin.clearProfile()
    }

    @Test
    fun readsSessionFromSignedInProfile() {
        val browser = browser ?: return
        runBlocking {
            BrowserLogin.run(browser, YouTubeLogin.profileDir, "http://music.meld.test:$port/seed", args) { cdp ->
                cdp.cookies("meld.test")["SAPISID"]?.let { }
            }.getOrThrow()
            val auth =
                YouTubeLogin.readSession(
                    browser,
                    musicUrl = "http://music.meld.test:$port",
                    extraArgs = args,
                    cookieDomain = "meld.test",
                ).getOrThrow()
            assertTrue("SAPISID=test-sapisid" in auth.cookie)
            assertTrue("SID=test-sid" in auth.cookie)
            assertEquals("test-visitor", auth.visitorData)
            assertEquals("sync-id", auth.dataSyncId)
            assertEquals("1", auth.authUser)
        }
    }

    @Test
    fun reportsWhenTheUserDidNotSignIn() {
        val browser = browser ?: return
        val result =
            runBlocking {
                YouTubeLogin.readSession(
                    browser,
                    musicUrl = "http://music.meld.test:$port",
                    extraArgs = args,
                    cookieDomain = "meld.test",
                )
            }
        assertEquals(YouTubeLogin.NOT_SIGNED_IN, result.exceptionOrNull()?.message)
    }
}
