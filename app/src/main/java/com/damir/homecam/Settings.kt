package com.damir.homecam

import android.content.Context
import androidx.camera.core.CameraSelector

/** Persisted user settings, stored in a single SharedPreferences file. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(1024, 65535)).apply()

    var username: String
        get() = prefs.getString(KEY_USER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USER, value.trim()).apply()

    var password: String
        get() = prefs.getString(KEY_PASS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PASS, value).apply()

    var useFrontCamera: Boolean
        get() = prefs.getBoolean(KEY_FRONT, false)
        set(value) = prefs.edit().putBoolean(KEY_FRONT, value).apply()

    var jpegQuality: Int
        get() = prefs.getInt(KEY_QUALITY, DEFAULT_QUALITY)
        set(value) = prefs.edit().putInt(KEY_QUALITY, value.coerceIn(10, 100)).apply()

    /** Audio capture from the microphone, exposed on the /audio.pcm endpoint. */
    var audioEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO, value).apply()

    /** Motion detection on the camera frames. */
    var motionEnabled: Boolean
        get() = prefs.getBoolean(KEY_MOTION, false)
        set(value) = prefs.edit().putBoolean(KEY_MOTION, value).apply()

    /** 1 (least sensitive) .. 100 (most sensitive). */
    var motionSensitivity: Int
        get() = prefs.getInt(KEY_MOTION_SENS, DEFAULT_SENSITIVITY)
        set(value) = prefs.edit().putInt(KEY_MOTION_SENS, value.coerceIn(1, 100)).apply()

    /** "off" | "motion" | "continuous" */
    var recordMode: String
        get() = prefs.getString(KEY_RECORD, RECORD_OFF) ?: RECORD_OFF
        set(value) = prefs.edit().putString(KEY_RECORD, value).apply()

    /** Length of a clip (motion mode: trailing silence; continuous: segment size). */
    var clipSeconds: Int
        get() = prefs.getInt(KEY_CLIP_SECS, DEFAULT_CLIP_SECONDS)
        set(value) = prefs.edit().putInt(KEY_CLIP_SECS, value.coerceIn(5, 600)).apply()

    val lensFacing: Int
        get() = if (useFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK

    /** Basic-auth is enforced only when both a username and password are set. */
    val authRequired: Boolean
        get() = username.isNotEmpty() && password.isNotEmpty()

    val recordingOn: Boolean
        get() = recordMode == RECORD_MOTION || recordMode == RECORD_CONTINUOUS

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_QUALITY = 55
        const val DEFAULT_SENSITIVITY = 50
        const val DEFAULT_CLIP_SECONDS = 20

        const val RECORD_OFF = "off"
        const val RECORD_MOTION = "motion"
        const val RECORD_CONTINUOUS = "continuous"

        private const val PREFS = "homecam"
        private const val KEY_PORT = "port"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
        private const val KEY_FRONT = "front"
        private const val KEY_QUALITY = "quality"
        private const val KEY_AUDIO = "audio"
        private const val KEY_MOTION = "motion"
        private const val KEY_MOTION_SENS = "motion_sens"
        private const val KEY_RECORD = "record_mode"
        private const val KEY_CLIP_SECS = "clip_secs"
    }
}
