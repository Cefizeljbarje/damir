package com.damir.homecamviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Date

/** A camera's state and settings, from its /api/status endpoint. */
data class CameraStatus(
    val name: String,
    val motionEnabled: Boolean,
    val motionSensitivity: Int,
    val recordMode: String,
    val recordingAvailable: Boolean,
    val recording: Boolean,
    val audio: Boolean,
    /** -1 automatic, else 0/90/180/270 degrees clockwise. */
    val rotation: Int,
    /** 0..359, -1 lying flat, [NO_SENSOR_DATA] none. */
    val sensorOrientation: Int,
    /** Epoch ms, 0 = none since the camera started. */
    val lastMotion: Long,
    /** Percent, -1 unknown. */
    val battery: Int,
    val charging: Boolean
) {
    /** One line for the camera list. */
    fun shortSummary(context: Context): String = listOfNotNull(
        context.getString(R.string.status_online),
        batteryText(context),
        context.getString(if (motionEnabled) R.string.status_motion_on else R.string.status_motion_off),
        if (recording) context.getString(R.string.status_recording) else null
    ).joinToString(" · ")

    /** A few lines for the camera settings screen. */
    fun details(context: Context): String {
        val motion = if (lastMotion > 0) {
            context.getString(R.string.status_last_motion, DateFormat.getTimeFormat(context).format(Date(lastMotion)))
        } else {
            context.getString(R.string.status_no_motion)
        }
        val sensor = when {
            sensorOrientation == NO_SENSOR_DATA -> context.getString(R.string.status_sensor_none)
            sensorOrientation < 0 -> context.getString(R.string.status_sensor_flat)
            else -> context.getString(R.string.status_sensor, sensorOrientation)
        }
        return listOfNotNull(
            listOfNotNull(context.getString(R.string.status_online), batteryText(context), name.ifEmpty { null })
                .joinToString(" · "),
            motion + if (recording) " · " + context.getString(R.string.status_recording) else "",
            sensor,
            if (!recordingAvailable && recordMode != "off") context.getString(R.string.status_no_recording) else null
        ).joinToString("\n")
    }

    private fun batteryText(context: Context): String? {
        if (battery < 0) return null
        val text = context.getString(R.string.status_battery, battery)
        return if (charging) text + ", " + context.getString(R.string.status_charging) else text
    }

    companion object {
        const val NO_SENSOR_DATA = Int.MIN_VALUE

        fun fromJson(json: String): CameraStatus {
            val o = JSONObject(json)
            return CameraStatus(
                name = o.optString("name"),
                motionEnabled = o.optBoolean("motionEnabled"),
                motionSensitivity = o.optInt("motionSensitivity", 50),
                recordMode = o.optString("recordMode", "off"),
                recordingAvailable = o.optBoolean("recordingAvailable"),
                recording = o.optBoolean("recording"),
                audio = o.optBoolean("audio"),
                rotation = o.optInt("rotation", -1),
                sensorOrientation = o.optInt("sensorOrientation", NO_SENSOR_DATA),
                lastMotion = o.optLong("lastMotion", 0L),
                battery = o.optInt("battery", -1),
                charging = o.optBoolean("charging")
            )
        }
    }
}

/** Blocking HTTP calls to a camera; call them off the main thread. */
object CameraApi {

    fun status(camera: Camera): CameraStatus =
        CameraStatus.fromJson(read(open(camera, camera.statusUrl)).decodeToString())

    /** Changes the given settings and returns the camera's new state. */
    fun changeSettings(camera: Camera, fields: Map<String, String>): CameraStatus {
        val body = fields.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val conn = open(camera, camera.settingsUrl).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("X-HomeCam", "1") // required by the camera for changes
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return CameraStatus.fromJson(read(conn).decodeToString())
    }

    /** The current picture, or null when the camera can't be reached. */
    fun snapshot(camera: Camera): Bitmap? = runCatching {
        val bytes = read(open(camera, camera.snapshotUrl))
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    fun open(camera: Camera, url: String, readTimeoutMs: Int = 8000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = readTimeoutMs
            camera.authHeader()?.let { setRequestProperty("Authorization", it) }
        }

    private fun read(conn: HttpURLConnection): ByteArray {
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
