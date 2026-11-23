package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Runs the Mahak engine (libmahak.so, a static arm64 program staged like the Psiphon and lyrebird
 * programs) beside Xray for as long as a session needs it. A session needs it when its configuration
 * has a SOCKS outbound that dials 127.0.0.1:[PORT]; no other setting is involved.
 *
 * The engine takes the TLS connections Xray hands it, tries ways of sending the first ClientHello
 * that DPI does not recognise, and keeps what works on the current network. See the mahak sources.
 *
 * Its log lines are written at warning level on purpose: the log screen of the app hides the lower
 * levels, and a sidecar that stays silent cannot be told from one that never started.
 */
object MahakSidecar {

    const val PORT = 10850
    private const val LIB = "libmahak.so"
    private const val LISTEN_WAIT_MS = 2500L
    private val PORT_TEXT = Regex("\"port\"\\s*:\\s*${PORT}(\\.0)?\\b")

    @Volatile
    private var process: Process? = null

    /**
     * True when [content], the configuration handed to Xray, has a SOCKS outbound that dials the engine.
     * The configuration may have been read into the app's own model and written out again, so when its
     * structure does not show the outbound, the text is searched for the address and the port.
     */
    fun wanted(content: String): Boolean {
        val structured = try {
            val outbounds = JsonParser.parseString(content).asJsonObject.get("outbounds")
            outbounds != null && outbounds.isJsonArray && outbounds.asJsonArray.any { dials(it) }
        } catch (_: Exception) {
            false
        }
        return structured || (content.contains(AppConfig.LOOPBACK) && PORT_TEXT.containsMatchIn(content))
    }

    private fun hit(x: JsonObject): Boolean =
        x.get("address")?.asString == AppConfig.LOOPBACK && x.get("port")?.asInt == PORT

    private fun dials(element: JsonElement): Boolean {
        val outbound = element.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        if (outbound.get("protocol")?.asString?.equals("socks", ignoreCase = true) != true) return false
        val settings = outbound.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        if (hit(settings)) return true
        val servers = settings.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        return servers.any { it.isJsonObject && hit(it.asJsonObject) }
    }

    private fun outboundsPreview(content: String): String = try {
        JsonParser.parseString(content).asJsonObject.get("outbounds")?.toString()?.take(700) ?: "none"
    } catch (e: Exception) {
        "unreadable (${e.javaClass.simpleName})"
    }

    private fun isListening(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(AppConfig.LOOPBACK, PORT), 100) }
        true
    } catch (_: Exception) {
        false
    }

    /** Starts the engine when [content] asks for it, and returns once it listens (or after a short wait). */
    @Synchronized
    fun start(context: Context, content: String) {
        stopLocked()
        val want = wanted(content)
        LogUtil.w(AppConfig.TAG, "Mahak: asked for by the configuration: $want")
        if (!want) {
            LogUtil.w(AppConfig.TAG, "Mahak: outbounds seen: ${outboundsPreview(content)}")
            return
        }
        val bin = File(context.applicationInfo.nativeLibraryDir, LIB)
        LogUtil.w(AppConfig.TAG, "Mahak: program ${bin.path} exists=${bin.exists()} executable=${bin.canExecute()}")
        if (!bin.exists()) return
        val dir = File(context.filesDir, "mahak").apply { mkdirs() }
        try {
            val builder = ProcessBuilder(
                bin.path, "serve",
                "-listen", "${AppConfig.LOOPBACK}:$PORT",
                "-state", File(dir, "state.json").path,
                "-v",
            )
            builder.environment()["HOME"] = dir.path
            // Android keeps its trusted roots in these directories; the program is a plain Linux binary.
            builder.environment()["SSL_CERT_DIR"] = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
            builder.redirectErrorStream(true)
            val started = builder.start()
            process = started
            Thread({
                try {
                    started.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { LogUtil.w(AppConfig.TAG, "Mahak: $it") }
                    }
                } catch (_: Exception) {
                }
            }, "mahak-log").apply { isDaemon = true }.start()
            Thread({
                try {
                    val code = started.waitFor()
                    LogUtil.w(AppConfig.TAG, "Mahak: the program ended with code $code")
                } catch (_: InterruptedException) {
                }
            }, "mahak-wait").apply { isDaemon = true }.start()
            awaitListening()
            LogUtil.w(AppConfig.TAG, "Mahak: alive=${started.isAlive} listening=${isListening()}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Mahak: could not start the program", e)
            stopLocked()
        }
    }

    private fun awaitListening() {
        val deadline = System.currentTimeMillis() + LISTEN_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isListening()) return
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        val running = process ?: return
        process = null
        running.destroy()
        try {
            if (!running.waitFor(500, TimeUnit.MILLISECONDS)) running.destroyForcibly()
        } catch (_: InterruptedException) {
            running.destroyForcibly()
        }
    }
}
