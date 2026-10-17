package com.damir.homecam

/**
 * In-process hand-off of the latest JPEG frame from the camera pipeline to any
 * number of MJPEG stream clients (and the local preview in the activity).
 *
 * The camera thread calls [publish]; each client thread calls [awaitNewer] to
 * block until a frame it has not seen yet is available.
 */
object FrameBus {

    private val lock = Object()

    @Volatile
    private var latest: ByteArray? = null
    private var seq: Long = 0

    /** Publish a new frame and wake every waiting client. */
    fun publish(jpeg: ByteArray) {
        synchronized(lock) {
            latest = jpeg
            seq++
            lock.notifyAll()
        }
    }

    /**
     * Block until a frame newer than [lastSeq] is available or [timeoutMs]
     * elapses. Returns the frame bytes and its sequence number, or null on
     * timeout. Pass 0 as [lastSeq] on the first call to get the current frame.
     */
    fun awaitNewer(lastSeq: Long, timeoutMs: Long): Frame? {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (latest == null || seq == lastSeq) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                lock.wait(remaining)
            }
            return Frame(latest!!, seq)
        }
    }

    /** The most recent frame without blocking, or null if none yet. */
    fun snapshot(): Frame? = synchronized(lock) {
        val l = latest ?: return null
        Frame(l, seq)
    }

    /** Clear the retained frame when streaming stops. */
    fun clear() {
        synchronized(lock) {
            latest = null
            lock.notifyAll()
        }
    }

    class Frame(val bytes: ByteArray, val seq: Long)
}
