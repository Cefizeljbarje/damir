package com.damir.homecam

/** What the HTTP API may read and change on the running camera. */
interface CameraControl {

    /** The camera's current state and settings, as a JSON object. */
    fun statusJson(): String

    /** Applies the given form fields; unknown fields and invalid values are ignored. */
    fun applySettings(fields: Map<String, String>)
}
