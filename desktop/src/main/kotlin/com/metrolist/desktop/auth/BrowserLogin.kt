package com.metrolist.desktop.auth

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Desktop replacement for the Android app's WebView logins. Opens a sign-in page in a separate
 * Microsoft Edge (or Chrome) window with its own profile, then reads what the session needs through
 * the browser's DevTools protocol once the user has signed in. Edge ships with Windows, so nothing
 * extra needs to be installed.
 */
object BrowserLogin {
    private const val LOGIN_TIMEOUT_MS = 10 * 60 * 1000L

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

    fun requireBrowser(): File =
        findBrowser() ?: throw IllegalStateException("Microsoft Edge or Google Chrome is needed to sign in")

    /** Thrown by a poll to stop the login with [message] instead of trying again. */
    class LoginAbort(message: String) : Exception(message)

    /**
     * Opens [url] in a plain browser window using [profileDir], with no DevTools connection, and
     * suspends until the user closes it. Google refuses sign-in in windows it can tell are being
     * controlled, so its login has to happen in a window like this before [run] reads the result.
     */
    suspend fun openWindow(
        browser: File,
        profileDir: File,
        url: String,
        extraArgs: List<String> = emptyList(),
        timeoutMs: Long = LOGIN_TIMEOUT_MS,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            val dir = profileDir.apply { mkdirs() }
            val process =
                ProcessBuilder(
                    browser.absolutePath,
                    "--user-data-dir=${dir.absolutePath}",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-background-mode",
                    "--window-size=520,760",
                    *extraArgs.toTypedArray(),
                    "--app=$url",
                ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try {
                withTimeout(timeoutMs) {
                    // The browser holds a lock file in the profile while it runs. Waiting for it to go away
                    // (rather than only for the process) also covers a launcher that hands off and exits.
                    var sawLock = false
                    var idleChecks = 0
                    while (true) {
                        delay(500)
                        val locked = isProfileLocked(dir)
                        sawLock = sawLock || locked
                        if (locked || process.isAlive) {
                            idleChecks = 0
                            continue
                        }
                        // Process gone and no lock: closed, or a hand-off whose lock hasn't appeared yet.
                        if (sawLock || ++idleChecks >= 10) break
                    }
                }
                Result.success(Unit)
            } catch (e: TimeoutCancellationException) {
                Result.failure(IllegalStateException("Sign-in timed out"))
            } finally {
                if (process.isAlive) process.destroy()
            }
        }

    /**
     * Opens [url] in a login window using [profileDir] and calls [poll] every 1.5 seconds until it
     * returns a value, the user closes the window, or [timeoutMs] passes.
     */
    suspend fun <T : Any> run(
        browser: File,
        profileDir: File,
        url: String,
        extraArgs: List<String> = emptyList(),
        timeoutMs: Long = LOGIN_TIMEOUT_MS,
        poll: suspend (Cdp) -> T?,
    ): Result<T> =
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
                withTimeout(timeoutMs) {
                    val client = Cdp.connect(waitForDevTools(portFile)).also { cdp = it }
                    var value: T? = null
                    while (value == null) {
                        delay(1500)
                        if (client.closed.isCompleted) error("The sign-in window was closed")
                        value = runCatching { poll(client) }
                            .onFailure {
                                if (it is CancellationException || it is LoginAbort) throw it
                                Timber.d("Login poll failed: ${it.message}")
                            }.getOrNull()
                    }
                    Result.success(value)
                }
            } catch (e: TimeoutCancellationException) {
                Result.failure(IllegalStateException("Sign-in timed out"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Browser login failed")
                Result.failure(e)
            } finally {
                withContext(NonCancellable) {
                    runCatching { cdp?.send("Browser.close") }
                    delay(500)
                    if (process.isAlive) process.destroy()
                }
            }
        }

    /**
     * Whether a browser is running on [dir]. On Windows the profile's "lockfile" is held open, so it
     * can't be deleted while the browser runs; elsewhere "SingletonLock" is a symlink to "host-pid",
     * which can outlive a browser that was killed.
     */
    private fun isProfileLocked(dir: File): Boolean {
        val windowsLock = File(dir, "lockfile")
        if (windowsLock.exists()) return !windowsLock.delete()
        val link = File(dir, "SingletonLock").toPath()
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) return false
        val pid = runCatching { Files.readSymbolicLink(link).toString().substringAfterLast('-').toLong() }.getOrNull()
        return pid == null || ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
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
    class Cdp private constructor() : WebSocket.Listener {
        private lateinit var socket: WebSocket
        private val nextId = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        private val sessions = ConcurrentHashMap<String, String>()
        private val buffer = StringBuilder()
        val closed = CompletableDeferred<Unit>()

        suspend fun send(method: String, params: JsonObject? = null, sessionId: String? = null): JsonObject {
            val id = nextId.getAndIncrement()
            val reply = CompletableDeferred<JsonObject>().also { pending[id] = it }
            val message = buildJsonObject {
                put("id", id)
                put("method", method)
                params?.let { put("params", it) }
                sessionId?.let { put("sessionId", it) }
            }
            socket.sendText(message.toString(), true).await()
            val response = withTimeout(10_000) { reply.await() }
            response["error"]?.let { error("$method failed: $it") }
            return response["result"]?.jsonObject ?: JsonObject(emptyMap())
        }

        /** All cookies of the login profile whose domain ends with [domainSuffix], as name to value. */
        suspend fun cookies(domainSuffix: String): Map<String, String> =
            send("Storage.getCookies")["cookies"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it["domain"]?.jsonPrimitive?.content.orEmpty().endsWith(domainSuffix) }
                .associate { it["name"]!!.jsonPrimitive.content to it["value"]!!.jsonPrimitive.content }

        /**
         * Evaluates [expression] in the first open page whose URL starts with [urlPrefix] and returns
         * its value, or null when no such page is open yet.
         */
        suspend fun evaluate(urlPrefix: String, expression: String): JsonElement? {
            val target =
                send("Target.getTargets")["targetInfos"]?.jsonArray.orEmpty()
                    .map { it.jsonObject }
                    .firstOrNull {
                        it["type"]?.jsonPrimitive?.content == "page" &&
                            it["url"]?.jsonPrimitive?.content.orEmpty().startsWith(urlPrefix)
                    }?.get("targetId")?.jsonPrimitive?.content ?: return null
            val session =
                sessions[target] ?: send(
                    "Target.attachToTarget",
                    buildJsonObject {
                        put("targetId", target)
                        put("flatten", true)
                    },
                )["sessionId"]!!.jsonPrimitive.content.also { sessions[target] = it }
            val result =
                try {
                    send(
                        "Runtime.evaluate",
                        buildJsonObject {
                            put("expression", expression)
                            put("returnByValue", true)
                        },
                        session,
                    )
                } catch (e: Exception) {
                    sessions.remove(target)
                    throw e
                }
            return result["result"]?.jsonObject?.get("value")
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val message = runCatching { Json.parseToJsonElement(buffer.toString()).jsonObject }.getOrNull()
                buffer.setLength(0)
                message?.get("id")?.jsonPrimitive?.int?.let { pending.remove(it)?.complete(message) }
                // A page that navigated away drops its session; attach again on the next call.
                if (message?.get("method")?.jsonPrimitive?.contentOrNull == "Target.detachedFromTarget") {
                    val sessionId = (message["params"]?.jsonObject?.get("sessionId") as? JsonPrimitive)?.contentOrNull
                    sessions.entries.removeIf { it.value == sessionId }
                }
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
