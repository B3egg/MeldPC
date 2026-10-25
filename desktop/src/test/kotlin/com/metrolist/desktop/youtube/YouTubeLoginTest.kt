package com.metrolist.desktop.youtube

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives a real Chromium through the login flow against fake sign-in and YouTube Music pages.
 * Google's and YouTube's real hostnames are HSTS-preloaded, so Chromium would force them onto HTTPS;
 * the fake pages use test hostnames instead. Runs only when MELD_TEST_BROWSER points at a
 * Chromium-based browser.
 */
class YouTubeLoginTest {
    @Test
    fun readsCookiesAndPageConfigAfterSignIn() {
        val browser = System.getenv("MELD_TEST_BROWSER")?.let(::File)?.takeIf { it.canExecute() } ?: return
        lateinit var server: HttpServer
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { ex ->
                    val port = server.address.port
                    val page =
                        if (ex.requestHeaders.getFirst("Host").startsWith("accounts.meld.test")) {
                            // Pretend the user signs in after a moment, then Google sends them on to YouTube Music.
                            """
                            <html><body>Sign in<script>
                            setTimeout(() => { location.href = "http://music.meld.test:$port/"; }, 1500);
                            </script></body></html>
                            """
                        } else {
                            """
                            <html><body>YouTube Music<script>
                            document.cookie = "SAPISID=test-sapisid; domain=.meld.test; path=/";
                            document.cookie = "SID=test-sid; domain=.meld.test; path=/";
                            window.yt = { config_: { VISITOR_DATA: "test-visitor", DATASYNC_ID: "sync-id||other", SESSION_INDEX: 1 } };
                            </script></body></html>
                            """
                        }.trimIndent().toByteArray()
                    ex.sendResponseHeaders(200, page.size.toLong())
                    ex.responseBody.use { it.write(page) }
                }
                start()
            }
        try {
            val port = server.address.port
            val auth =
                runBlocking {
                    YouTubeLogin.login(
                        browser = browser,
                        url = "http://accounts.meld.test:$port/ServiceLogin",
                        extraArgs =
                            listOf(
                                "--headless=new",
                                "--no-sandbox",
                                "--host-resolver-rules=MAP *.meld.test 127.0.0.1",
                            ),
                        musicUrl = "http://music.meld.test:$port",
                        cookieDomain = "meld.test",
                        timeoutMs = 60_000,
                    )
                }.getOrThrow()
            assertTrue("SAPISID=test-sapisid" in auth.cookie)
            assertTrue("SID=test-sid" in auth.cookie)
            assertEquals("test-visitor", auth.visitorData)
            assertEquals("sync-id", auth.dataSyncId)
            assertEquals("1", auth.authUser)
        } finally {
            server.stop(0)
            YouTubeLogin.clearProfile()
        }
    }
}
