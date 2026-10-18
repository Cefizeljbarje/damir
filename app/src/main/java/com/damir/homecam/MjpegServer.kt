package com.damir.homecam

import android.util.Base64
import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A tiny HTTP server that serves the camera feed on the local network:
 *
 *   GET /                 -> a minimal HTML viewer page
 *   GET /video            -> multipart/x-mixed-replace MJPEG stream
 *   GET /snapshot         -> a single current JPEG
 *   GET /audio.pcm        -> raw 16 kHz mono s16le PCM (for the viewer app)
 *   GET /recordings       -> HTML index of recorded clips
 *   GET /recordings/<f>   -> download a recorded .mp4 clip
 *   GET /healthz          -> "ok"
 *
 * When a username and password are configured, every route requires HTTP Basic
 * auth. Implemented with raw sockets so the app needs no networking library.
 */
class MjpegServer(
    private val port: Int,
    private val username: String,
    private val password: String,
    private val recordingsDirProvider: () -> File? = { null }
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

            when {
                path == "/" -> writeSimple(out, 200, "OK", "text/html; charset=utf-8", viewerHtml())
                path == "/healthz" -> writeSimple(out, 200, "OK", "text/plain", "ok")
                path == "/snapshot" -> writeSnapshot(out)
                path == "/video" -> writeMjpeg(socket, out)
                path == "/audio.pcm" -> writeAudio(socket, out)
                path == "/recordings" || path == "/recordings/" -> writeRecordingsIndex(out)
                path.startsWith("/recordings/") -> writeRecording(out, path.removePrefix("/recordings/"))
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

    private fun writeAudio(socket: Socket, out: OutputStream) {
        val header = buildString {
            append("HTTP/1.0 200 OK\r\n")
            append("Cache-Control: no-cache, no-store\r\n")
            append("Connection: close\r\n")
            append("Content-Type: audio/L16; rate=${AudioBus.SAMPLE_RATE}; channels=${AudioBus.CHANNELS}\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.flush()

        val queue: LinkedBlockingQueue<ByteArray> = AudioBus.register()
        try {
            while (running && !socket.isClosed) {
                val chunk = queue.poll(5, TimeUnit.SECONDS) ?: continue
                out.write(chunk)
                out.flush()
            }
        } finally {
            AudioBus.unregister(queue)
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

    private fun writeRecordingsIndex(out: OutputStream) {
        val dir = recordingsDirProvider()
        val files = dir?.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        val rows = if (files.isEmpty()) {
            "<p>No recordings yet.</p>"
        } else {
            files.joinToString("\n") { f ->
                val sizeMb = f.length() / (1024.0 * 1024.0)
                "<li><a href=\"/recordings/${f.name}\">${f.name}</a> " +
                    "<span class=\"s\">(${"%.1f".format(sizeMb)} MB)</span></li>"
            }.let { "<ul>$it</ul>" }
        }
        val html = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>HomeCam recordings</title>
            <style>:root{color-scheme:dark}body{font-family:system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:16px}
            a{color:#4db6ac}li{margin:6px 0}.s{opacity:.6;font-size:12px}</style></head>
            <body><h2>Recordings</h2>$rows<p><a href="/">&larr; live view</a></p></body></html>
        """.trimIndent()
        writeSimple(out, 200, "OK", "text/html; charset=utf-8", html)
    }

    private fun writeRecording(out: OutputStream, rawName: String) {
        val name = rawName.substringBefore('?')
        // Reject path traversal; only serve plain file names.
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            writeSimple(out, 400, "Bad Request", "text/plain", "bad name")
            return
        }
        val dir = recordingsDirProvider()
        val file = dir?.let { File(it, name) }
        if (file == null || !file.exists() || !file.isFile) {
            writeSimple(out, 404, "Not Found", "text/plain", "not found")
            return
        }
        val header = buildString {
            append("HTTP/1.0 200 OK\r\n")
            append("Content-Type: video/mp4\r\n")
            append("Content-Length: ${file.length()}\r\n")
            append("Content-Disposition: attachment; filename=\"$name\"\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
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
            img { max-width:100%; max-height:85vh; background:#000; }
            #status { padding:8px; font-size:14px; opacity:.7; }
            nav { padding:10px; font-size:14px; }
            nav a { color:#4db6ac; margin:0 8px; }
            footer { padding:10px; font-size:12px; opacity:.6; }
          </style>
        </head>
        <body>
          <header>HomeCam &middot; live</header>
          <img id="live" alt="live stream">
          <noscript><img src="/video" alt="live stream"></noscript>
          <div id="status">Connecting&hellip;</div>
          <nav><a href="/recordings">Recordings</a><a href="/snapshot">Snapshot</a></nav>
          <footer>Streaming over your local network &middot; audio at /audio.pcm</footer>
          <script>
          // The page reads the stream itself instead of <img src="/video">: that
          // img froze on its last frame whenever the connection dropped (e.g. the
          // camera restarted). Here every frame is its own image, and a dropped or
          // stalled stream reconnects by itself.
          (function () {
            var img = document.getElementById('live');
            var status = document.getElementById('status');
            if (!window.fetch || !window.ReadableStream || !window.AbortController) {
              img.src = '/video';
              status.hidden = true;
              return;
            }

            // Show one frame at a time; while one decodes, keep only the newest.
            var shown = null, pending = null, busy = false;
            function show(bytes) {
              pending = bytes;
              if (!busy) next();
            }
            function next() {
              if (!pending) { busy = false; return; }
              busy = true;
              var url = URL.createObjectURL(new Blob([pending], { type: 'image/jpeg' }));
              pending = null;
              img.onload = img.onerror = function () {
                if (shown) URL.revokeObjectURL(shown);
                shown = url;
                next();
              };
              img.src = url;
            }

            // Cuts the MJPEG stream into JPEGs at the FF D8 / FF D9 markers.
            async function play() {
              var abort = new AbortController();
              var lastFrame = Date.now();
              var watchdog = setInterval(function () {
                if (Date.now() - lastFrame > 8000) abort.abort();
              }, 1000);
              try {
                var res = await fetch('/video', { cache: 'no-store', signal: abort.signal });
                if (!res.ok) throw new Error('HTTP ' + res.status);
                var reader = res.body.getReader();
                var buf = new Uint8Array(0), start = -1, scan = 1;
                for (;;) {
                  var r = await reader.read();
                  if (r.done) return;
                  var joined = new Uint8Array(buf.length + r.value.length);
                  joined.set(buf);
                  joined.set(r.value, buf.length);
                  buf = joined;
                  var i = scan;
                  while (i < buf.length) {
                    if (buf[i - 1] === 0xFF && start < 0 && buf[i] === 0xD8) {
                      start = i - 1;
                    } else if (buf[i - 1] === 0xFF && start >= 0 && buf[i] === 0xD9) {
                      show(buf.slice(start, i + 1));
                      lastFrame = Date.now();
                      status.hidden = true;
                      buf = buf.slice(i + 1);
                      start = -1;
                      i = 1;
                      continue;
                    }
                    i++;
                  }
                  if (start < 0) {
                    buf = buf.slice(Math.max(0, buf.length - 1));
                    scan = 1;
                  } else {
                    buf = buf.slice(start);
                    start = 0;
                    scan = buf.length;
                  }
                }
              } finally {
                clearInterval(watchdog);
              }
            }

            async function run() {
              for (;;) {
                try { await play(); } catch (e) { }
                status.textContent = 'Reconnecting…';
                status.hidden = false;
                await new Promise(function (done) { setTimeout(done, 2000); });
              }
            }
            run();
          })();
          </script>
        </body>
        </html>
    """.trimIndent()

    companion object {
        private const val TAG = "MjpegServer"
        private const val BOUNDARY = "homecamframe"
    }
}
