package com.damir.homecam

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny HTTP server that serves the camera feed on the local network:
 *
 *   GET /           -> a minimal HTML viewer page
 *   GET /video      -> multipart/x-mixed-replace MJPEG stream
 *   GET /snapshot   -> a single current JPEG
 *   GET /healthz    -> "ok"
 *
 * When a username and password are configured, every route requires HTTP Basic
 * auth. Implemented with raw sockets so the app needs no networking library.
 */
class MjpegServer(
    private val port: Int,
    private val username: String,
    private val password: String
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = CopyOnWriteArrayList<Socket>()

    private val authRequired = username.isNotEmpty() && password.isNotEmpty()
    private val expectedAuth: String by lazy {
        "Basic " + Base64.encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
    }

    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        running = true
        acceptThread = Thread({ acceptLoop(socket) }, "mjpeg-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Server listening on port $port")
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break // socket closed by stop()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept error", e)
                continue
            }
            Thread({ handleClient(client) }, "mjpeg-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        clients.add(socket)
        try {
            socket.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val out = BufferedOutputStream(socket.getOutputStream())

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                writeSimple(out, 400, "Bad Request", "text/plain", "bad request")
                return
            }
            val path = parts[1].substringBefore('?')

            // Consume headers, capture Authorization.
            var authHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Authorization:", ignoreCase = true)) {
                    authHeader = line.substringAfter(':').trim()
                }
            }

            if (authRequired && authHeader != expectedAuth) {
                writeUnauthorized(out)
                return
            }

            when (path) {
                "/" -> writeSimple(out, 200, "OK", "text/html; charset=utf-8", viewerHtml())
                "/healthz" -> writeSimple(out, 200, "OK", "text/plain", "ok")
                "/snapshot" -> writeSnapshot(out)
                "/video" -> writeMjpeg(socket, out)
                else -> writeSimple(out, 404, "Not Found", "text/plain", "not found")
            }
        } catch (_: Exception) {
            // client went away; nothing to do
        } finally {
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun writeMjpeg(socket: Socket, out: OutputStream) {
        val header = buildString {
            append("HTTP/1.0 200 OK\r\n")
            append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
            append("Pragma: no-cache\r\n")
            append("Connection: close\r\n")
            append("Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.flush()

        var lastSeq = 0L
        while (running && !socket.isClosed) {
            val frame = FrameBus.awaitNewer(lastSeq, 5000) ?: continue
            lastSeq = frame.seq
            val part = buildString {
                append("--$BOUNDARY\r\n")
                append("Content-Type: image/jpeg\r\n")
                append("Content-Length: ${frame.bytes.size}\r\n")
                append("\r\n")
            }
            out.write(part.toByteArray(StandardCharsets.UTF_8))
            out.write(frame.bytes)
            out.write("\r\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
    }

    private fun writeSnapshot(out: OutputStream) {
        val frame = FrameBus.snapshot()
        if (frame == null) {
            writeSimple(out, 503, "Service Unavailable", "text/plain", "no frame yet")
            return
        }
        val header = buildString {
            append("HTTP/1.0 200 OK\r\n")
            append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${frame.bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(frame.bytes)
        out.flush()
    }

    private fun writeUnauthorized(out: OutputStream) {
        val body = "401 Unauthorized"
        val header = buildString {
            append("HTTP/1.0 401 Unauthorized\r\n")
            append("WWW-Authenticate: Basic realm=\"HomeCam\"\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(body.toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun writeSimple(out: OutputStream, code: Int, reason: String, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.0 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun viewerHtml(): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>HomeCam</title>
          <style>
            :root { color-scheme: dark; }
            body { margin:0; background:#111; color:#eee; font-family:system-ui,sans-serif;
                   display:flex; flex-direction:column; align-items:center; }
            header { padding:12px; font-size:18px; font-weight:600; }
            img { max-width:100%; height:auto; background:#000; }
            footer { padding:10px; font-size:12px; opacity:.6; }
          </style>
        </head>
        <body>
          <header>HomeCam &middot; live</header>
          <img src="/video" alt="live stream"
               onerror="this.onerror=null;this.src='/snapshot';">
          <footer>Streaming over your local network</footer>
        </body>
        </html>
    """.trimIndent()

    companion object {
        private const val TAG = "MjpegServer"
        private const val BOUNDARY = "homecamframe"
    }
}
