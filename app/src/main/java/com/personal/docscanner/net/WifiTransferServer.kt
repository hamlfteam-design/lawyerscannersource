package com.personal.docscanner.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder

/**
 * Serves one file over plain HTTP to whatever opens its URL on the same
 * Wi-Fi network — a cable-free way to get a scanned case file onto the
 * office computer. There is no cloud step and no account: the socket only
 * ever answers a request from someone who already has the phone's own local
 * address (it is never reachable outside the LAN, and nothing is kept once
 * [stop] closes it), which is the whole point next to emailing the file to
 * yourself just to move it across a desk.
 *
 * This is a deliberately minimal HTTP/1.1 responder — one file, GET only,
 * whatever path is requested — not a general-purpose server. It reads and
 * discards the request line and stops there rather than parsing headers,
 * since every response is the same file regardless of what was asked for.
 */
class WifiTransferServer(
    private val file: File,
    private val downloadName: String,
    private val contentType: String = "application/pdf"
) {
    data class Info(val url: String, val fileSizeBytes: Long)

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Called on every completed request — the UI's cue for "تم الإرسال". */
    var onServed: (() -> Unit)? = null

    /** Starts listening, or returns null when there is no Wi-Fi/LAN address to serve on. */
    fun start(port: Int = DEFAULT_PORT): Info? {
        val address = localIpAddress() ?: return null
        val socket = runCatching { ServerSocket(port) }.getOrNull() ?: return null
        serverSocket = socket
        job = scope.launch { acceptLoop(socket) }
        val encodedName = URLEncoder.encode(downloadName, "UTF-8").replace("+", "%20")
        return Info(url = "http://$address:$port/$encodedName", fileSizeBytes = file.length())
    }

    fun stop() {
        job?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (t: Throwable) {
                break
            }
            runCatching { serve(client) }
            onServed?.invoke()
        }
    }

    private fun serve(client: Socket) {
        client.use { s ->
            runCatching { s.getInputStream().bufferedReader().readLine() }
            val out = s.getOutputStream()
            val bytes = file.readBytes()
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Content-Disposition: attachment; filename=\"$downloadName\"\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
    }

    /** The phone's own address on whatever LAN it is joined to — Wi-Fi or USB tethering alike. */
    private fun localIpAddress(): String? =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()

    companion object {
        const val DEFAULT_PORT = 8787
    }
}
