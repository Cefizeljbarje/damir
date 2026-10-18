package com.damir.homecam

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records MP4 clips with CameraX [Recorder]. Clips are **video only** (the
 * microphone is reserved for the live audio stream to avoid capture contention).
 *
 * Two modes are driven by the service:
 *  - motion: [onMotion] starts a clip; [tick] finalises it once no motion has
 *    been seen for `clipSeconds`.
 *  - continuous: [startContinuous] records back-to-back segments of
 *    `clipSeconds` each; [tick] rolls to a new file when a segment is full.
 *
 * All public methods must be called on the main thread.
 */
class ClipRecorder(private val context: Context) {

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var segmentStartMs = 0L
    private var lastMotionMs = 0L
    private var continuous = false

    val isRecording: Boolean get() = recording != null

    val recordingsDir: File by lazy {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        File(base, "clips").apply { if (!exists()) mkdirs() }
    }

    /** Builds the use case to bind to the camera lifecycle. */
    fun buildUseCase(): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        return VideoCapture.withOutput(recorder).also { videoCapture = it }
    }

    fun startContinuous() {
        continuous = true
        if (recording == null) startNewClip()
    }

    fun onMotion() {
        lastMotionMs = System.currentTimeMillis()
        if (recording == null) startNewClip()
    }

    /** Called roughly once per second by the service. */
    fun tick(clipSeconds: Int) {
        val now = System.currentTimeMillis()
        val active = recording ?: return
        if (continuous) {
            if (now - segmentStartMs >= clipSeconds * 1000L) {
                active.stop() // Finalize callback starts the next segment
            }
        } else {
            if (now - lastMotionMs >= clipSeconds * 1000L) {
                active.stop()
            }
        }
    }

    fun stop() {
        continuous = false
        recording?.stop()
        recording = null
    }

    private fun startNewClip() {
        val capture = videoCapture ?: return
        val name = "HomeCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val file = File(recordingsDir, name)
        val options = FileOutputOptions.Builder(file).build()
        try {
            recording = capture.output
                .prepareRecording(context, options)
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        if (event.hasError()) {
                            Log.w(TAG, "clip finalize error ${event.error}")
                        } else {
                            Log.i(TAG, "clip saved: ${file.name}")
                        }
                        recording = null
                        // In continuous mode, immediately begin the next segment.
                        if (continuous) startNewClip()
                    }
                }
            segmentStartMs = System.currentTimeMillis()
            if (lastMotionMs == 0L) lastMotionMs = segmentStartMs
        } catch (e: Exception) {
            Log.e(TAG, "failed to start clip", e)
            recording = null
        }
    }

    companion object {
        private const val TAG = "ClipRecorder"
    }
}
