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

    val lensFacing: Int
        get() = if (useFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK

    /** Basic-auth is enforced only when both a username and password are set. */
    val authRequired: Boolean
        get() = username.isNotEmpty() && password.isNotEmpty()

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_QUALITY = 55
        private const val PREFS = "homecam"
        private const val KEY_PORT = "port"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
        private const val KEY_FRONT = "front"
        private const val KEY_QUALITY = "quality"
    }
}
