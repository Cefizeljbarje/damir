package com.damir.homecamviewer

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Connects to an MJPEG endpoint and extracts individual JPEG frames by scanning
 * the byte stream for JPEG start (FF D8) and end (FF D9) markers. This is robust
 * regardless of how the server formats its multipart boundaries.
 *
 * Runs on its own daemon thread; callbacks fire on that thread.
 */
class MjpegReader(
    private val url: String,
    private val authHeader: String?,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (Exception) -> Unit
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var connection: HttpURLConnection? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "mjpeg-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { connection?.disconnect() }
        thread = null
    }

    private fun loop() {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                authHeader?.let { setRequestProperty("Authorization", it) }
            }
            connection = conn
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            BufferedInputStream(conn.inputStream, 1 shl 16).use { input ->
                parse(input)
            }
        } catch (e: Exception) {
            if (running) onError(e)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun parse(input: BufferedInputStream) {
        val block = ByteArray(1 shl 14)
        val buf = ByteArrayOutputStream(1 shl 18)
        var prev = -1
        var inFrame = false

        while (running) {
            val n = input.read(block)
            if (n < 0) break
            for (i in 0 until n) {
                val b = block[i].toInt() and 0xFF
                if (!inFrame) {
                    if (prev == 0xFF && b == 0xD8) {
                        inFrame = true
                        buf.reset()
                        buf.write(0xFF)
                        buf.write(0xD8)
                    }
                } else {
                    buf.write(b)
                    if (prev == 0xFF && b == 0xD9) {
                        inFrame = false
                        onFrame(buf.toByteArray())
                        buf.reset()
                    }
                }
                prev = b
            }
            // Guard against unbounded growth on a malformed stream.
            if (buf.size() > MAX_FRAME_BYTES) {
                buf.reset()
                inFrame = false
                Log.w(TAG, "discarding oversized frame buffer")
            }
        }
    }

    companion object {
        private const val TAG = "MjpegReader"
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    }
}
