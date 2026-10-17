package com.damir.homecamviewer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams raw 16 kHz mono s16le PCM from the camera's /audio.pcm endpoint and
 * plays it through [AudioTrack]. Runs on its own daemon thread.
 */
class AudioPlayer(
    private val url: String,
    private val authHeader: String?,
    private val onError: (Exception) -> Unit
) {
    private val sampleRate = 16000

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var connection: HttpURLConnection? = null
    private var track: AudioTrack? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "audio-player").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { connection?.disconnect() }
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
        thread = null
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError(IOException("AudioTrack unsupported"))
            return
        }
        val bufferSize = minBuf * 2
        val audioTrack = buildTrack(bufferSize)
        track = audioTrack

        try {
            audioTrack.play()
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
            BufferedInputStream(conn.inputStream, 1 shl 15).use { input ->
                val block = ByteArray(bufferSize)
                while (running) {
                    val n = input.read(block)
                    if (n < 0) break
                    if (n > 0) audioTrack.write(block, 0, n)
                }
            }
        } catch (e: Exception) {
            if (running) onError(e)
        } finally {
            runCatching { audioTrack.stop() }
            runCatching { audioTrack.release() }
            runCatching { connection?.disconnect() }
        }
    }

    private fun buildTrack(bufferSize: Int): AudioTrack {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }
    }

    companion object {
        private const val TAG = "AudioPlayer"
    }
}
