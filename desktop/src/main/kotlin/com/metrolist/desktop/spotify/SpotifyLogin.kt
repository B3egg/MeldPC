package com.metrolist.desktop.spotify

import com.metrolist.desktop.AppDirs
import com.metrolist.spotify.SpotifyAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class SpotifyCookies(val spDc: String, val spKey: String)

/**
 * Desktop replacement for the Android app's WebView login. Opens Spotify's login page in a
 * separate Microsoft Edge (or Chrome) window with its own profile, then reads the session
 * cookies through the browser's DevTools protocol once the user has signed in. Edge ships with
 * Windows, so nothing extra needs to be installed.
 */
object SpotifyLogin {
    private const val LOGIN_TIMEOUT_MS = 10 * 60 * 1000L

    val profileDir: File get() = AppDirs.file("spotify-login")

    fun findBrowser(): File? {
        val env = { name: String -> System.getenv(name)?.takeIf { it.isNotBlank() } }
        val candidates =
            listOfNotNull(
                env("ProgramFiles(x86)")?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
                env("ProgramFiles")?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
                env("ProgramFiles")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
                env("ProgramFiles(x86)")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
                env("LOCALAPPDATA")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
                "/usr/bin/microsoft-edge",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            )
        return candidates.map(::File).firstOrNull { it.canExecute() }
    }

    /** Opens the login window and suspends until the user has signed in or closed the window. */
    suspend fun login(): Result<SpotifyCookies> {
        val browser = findBrowser() ?: return Result.failure(IllegalStateException("Microsoft Edge or Google Chrome is needed to sign in"))
        return login(browser, SpotifyAuth.LOGIN_URL)
    }

    internal suspend fun login(
        browser: File,
        url: String,
        extraArgs: List<String> = emptyList(),
    ): Result<SpotifyCookies> =
        withContext(Dispatchers.IO) {
            val dir = profileDir.apply { mkdirs() }
            val portFile = File(dir, "DevToolsActivePort").apply { delete() }

            val process =
                ProcessBuilder(
                    browser.absolutePath,
                    "--user-data-dir=${dir.absolutePath}",
                    "--remote-debugging-port=0",
                    "--remote-allow-origins=*",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--window-size=520,760",
                    *extraArgs.toTypedArray(),
                    "--app=$url",
                ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

            var cdp: Cdp? = null
            try {
                withTimeout(LOGIN_TIMEOUT_MS) {
                    val client = Cdp.connect(waitForDevTools(portFile)).also { cdp = it }
                    var cookies: SpotifyCookies? = null
                    while (cookies == null) {
                        delay(1500)
                        if (client.closed.isCompleted) error("The sign-in window was closed")
                        val all = runCatching { client.cookies() }.getOrNull() ?: continue
                        cookies = all["sp_dc"]?.takeIf { it.isNotBlank() }?.let { SpotifyCookies(it, all["sp_key"].orEmpty()) }
                    }
                    Result.success(cookies)
                }
            } catch (e: TimeoutCancellationException) {
                Result.failure(IllegalStateException("Sign-in timed out"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Spotify login failed")
                Result.failure(e)
            } finally {
                withContext(NonCancellable) {
                    runCatching { cdp?.send("Browser.close") }
                    delay(500)
                    if (process.isAlive) process.destroy()
                }
            }
        }

    /** Removes the login window's browser profile, which also signs it out of Spotify. */
    fun clearProfile() {
        profileDir.deleteRecursively()
    }

    private suspend fun waitForDevTools(portFile: File): String {
        // The launcher process may hand off to another one, so wait on the file rather than the process.
        withTimeout(30_000) {
            while (!portFile.exists() || portFile.readLines().size < 2) delay(200)
        }
        val (port, path) = portFile.readLines()
        return "ws://127.0.0.1:$port$path"
    }

    /** Minimal Chrome DevTools Protocol client over the JDK's WebSocket. */
    private class Cdp private constructor() : WebSocket.Listener {
        private lateinit var socket: WebSocket
        private val nextId = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        private val buffer = StringBuilder()
        val closed = CompletableDeferred<Unit>()

        suspend fun send(method: String): JsonObject {
            val id = nextId.getAndIncrement()
            val reply = CompletableDeferred<JsonObject>().also { pending[id] = it }
            val message = buildJsonObject {
                put("id", id)
                put("method", method)
            }
            socket.sendText(message.toString(), true).await()
            return withTimeout(10_000) { reply.await() }
        }

        suspend fun cookies(): Map<String, String> =
            send("Storage.getCookies")["result"]?.jsonObject?.get("cookies")?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it["domain"]?.jsonPrimitive?.content.orEmpty().endsWith("spotify.com") }
                .associate { it["name"]!!.jsonPrimitive.content to it["value"]!!.jsonPrimitive.content }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val message = runCatching { Json.parseToJsonElement(buffer.toString()).jsonObject }.getOrNull()
                buffer.setLength(0)
                message?.get("id")?.jsonPrimitive?.int?.let { pending.remove(it)?.complete(message) }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
            closed.complete(Unit)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            closed.complete(Unit)
        }

        companion object {
            suspend fun connect(url: String): Cdp {
                val cdp = Cdp()
                cdp.socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI(url), cdp).await()
                return cdp
            }
        }
    }
}
