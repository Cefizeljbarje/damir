package com.damir.homecamviewer

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection

/**
 * Listens to a camera's /api/events stream (Server-Sent Events) on its own
 * thread and calls [onMotion] for each motion event. When the camera can't be
 * reached it retries with a growing delay. Callbacks run on the watcher thread.
 */
class EventWatcher(
    val camera: Camera,
    private val onMotion: (Camera, Long) -> Unit
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    @Volatile
    private var connection: HttpURLConnection? = null
    private var retryMs = MIN_RETRY_MS

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "events-${camera.id}").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { connection?.disconnect() }
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running) {
            try {
                listen()
            } catch (e: Exception) {
                if (running) Log.d(TAG, "events from ${camera.baseUrl}: ${e.message}")
            }
            if (!running) break
            try {
                Thread.sleep(retryMs)
            } catch (_: InterruptedException) {
                break
            }
            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    private fun listen() {
        // The camera sends a heartbeat every 45 s, so a silent minute or two means the link is gone.
        val conn = CameraApi.open(camera, camera.eventsUrl, readTimeoutMs = READ_TIMEOUT_MS)
        connection = conn
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            retryMs = MIN_RETRY_MS
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { readEvents(it) }
        } finally {
            conn.disconnect()
            connection = null
        }
    }

    /** An event is "event:" and "data:" lines ended by a blank line; ":" lines are heartbeats. */
    private fun readEvents(reader: BufferedReader) {
        var event = ""
        var data = ""
        while (running) {
            val line = reader.readLine() ?: return
            when {
                line.isEmpty() -> {
                    if (event == "motion") onMotion(camera, parseTime(data))
                    event = ""
                    data = ""
                }
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.substringAfter(':').trim()
                line.startsWith("data:") -> data = line.substringAfter(':').trim()
            }
        }
    }

    private fun parseTime(data: String): Long =
        runCatching { JSONObject(data).getLong("time") }.getOrDefault(System.currentTimeMillis())

    companion object {
        private const val TAG = "EventWatcher"
        private const val MIN_RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val READ_TIMEOUT_MS = 120_000
    }
}
