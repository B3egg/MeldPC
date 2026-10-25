package com.metrolist.desktop.spotify

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives a real Chromium through the login flow against a fake "accounts.spotify.com" page that
 * sets the session cookies after a short delay. Runs only when MELD_TEST_BROWSER points at a
 * Chromium-based browser, since CI machines don't always have one.
 */
class SpotifyLoginTest {
    @Test
    fun readsSessionCookiesOnceTheyAreSet() {
        val browser = System.getenv("MELD_TEST_BROWSER")?.let(::File)?.takeIf { it.canExecute() } ?: return
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { ex ->
                    val page =
                        """
                        <html><body>Signing in...<script>
                        setTimeout(() => {
                          document.cookie = "sp_dc=test-dc; domain=.spotify.com; path=/";
                          document.cookie = "sp_key=test-key; domain=.spotify.com; path=/";
                        }, 2000);
                        </script></body></html>
                        """.trimIndent().toByteArray()
                    ex.sendResponseHeaders(200, page.size.toLong())
                    ex.responseBody.use { it.write(page) }
                }
                start()
            }
        try {
            val port = server.address.port
            val result =
                runBlocking {
                    SpotifyLogin.login(
                        browser = browser,
                        url = "http://accounts.spotify.com:$port/login",
                        extraArgs = listOf("--headless=new", "--no-sandbox", "--host-resolver-rules=MAP accounts.spotify.com 127.0.0.1"),
                    )
                }
            assertEquals(SpotifyCookies("test-dc", "test-key"), result.getOrThrow())
        } finally {
            server.stop(0)
            SpotifyLogin.clearProfile()
        }
    }
}
