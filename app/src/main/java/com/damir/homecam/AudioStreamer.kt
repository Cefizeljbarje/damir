package com.damir.homecam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Captures microphone audio with [AudioRecord] and pushes fixed-format PCM
 * chunks to [AudioBus]. Runs on its own thread. The caller must hold the
 * RECORD_AUDIO permission before calling [start].
 */
class AudioStreamer {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // permission is checked by the caller
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "audio-capture").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(
            AudioBus.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord unsupported on this device")
            return
        }
        val bufferSize = minBuf * 2
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioBus.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            record.release()
            return
        }

        try {
            record.startRecording()
            val chunk = ByteArray(bufferSize)
            while (running) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    AudioBus.publish(chunk.copyOf(read))
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord read error $read")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop error", e)
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    companion object {
        private const val TAG = "AudioStreamer"
    }
}
