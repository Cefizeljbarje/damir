package com.damir.homecamviewer

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/** A saved camera the viewer can connect to. [baseUrl] has no trailing slash. */
data class Camera(
    val id: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    /** Push a notification to this phone when the camera sees motion. */
    val notify: Boolean = false
) {
    val displayName get() = name.ifEmpty { baseUrl }
    val videoUrl get() = "$baseUrl/video"
    val snapshotUrl get() = "$baseUrl/snapshot"
    val audioUrl get() = "$baseUrl/audio.pcm"
    val recordingsUrl get() = "$baseUrl/recordings"
    val statusUrl get() = "$baseUrl/api/status"
    val settingsUrl get() = "$baseUrl/api/settings"
    val eventsUrl get() = "$baseUrl/api/events"

    fun authHeader(): String? {
        if (username.isEmpty() || password.isEmpty()) return null
        val raw = "$username:$password".toByteArray(StandardCharsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    companion object {
        /** Accepts "host:port", "http://host:port", trailing slashes, etc. */
        fun normalizeUrl(input: String): String? {
            var s = input.trim()
            if (s.isEmpty()) return null
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
                s = "http://$s"
            }
            s = s.trimEnd('/')
            // Minimal sanity check: must have a host after the scheme.
            val afterScheme = s.substringAfter("://")
            if (afterScheme.isEmpty()) return null
            return s
        }
    }
}

class CameraStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): MutableList<Camera> {
        val json = prefs.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        val list = ArrayList<Camera>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                Camera(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name"),
                    baseUrl = o.optString("url"),
                    username = o.optString("user"),
                    password = o.optString("pass"),
                    notify = o.optBoolean("notify", false)
                )
            )
        }
        return list
    }

    fun get(index: Int): Camera? = all().getOrNull(index)

    fun byId(id: String): Camera? = all().firstOrNull { it.id == id }

    /** Replaces the saved camera with the same id. */
    fun update(camera: Camera) {
        val list = all()
        val index = list.indexOfFirst { it.id == camera.id }
        if (index >= 0) {
            list[index] = camera
            persist(list)
        }
    }

    fun add(camera: Camera) {
        val list = all()
        list.add(camera)
        persist(list)
    }

    fun update(index: Int, camera: Camera) {
        val list = all()
        if (index in list.indices) {
            list[index] = camera
            persist(list)
        }
    }

    fun removeAt(index: Int) {
        val list = all()
        if (index in list.indices) {
            list.removeAt(index)
            persist(list)
        }
    }

    private fun persist(list: List<Camera>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("url", c.baseUrl)
                    .put("user", c.username)
                    .put("pass", c.password)
                    .put("notify", c.notify)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "cameras"
        private const val KEY = "list"
    }
}
