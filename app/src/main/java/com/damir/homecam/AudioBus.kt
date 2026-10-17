package com.damir.homecam

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * Fan-out of raw PCM audio chunks from the microphone to any number of HTTP
 * clients. Each client registers a bounded queue; when a queue is full the
 * oldest chunk is dropped so a slow client can never grow memory without bound.
 *
 * Format is fixed: 16 kHz, mono, signed 16-bit little-endian PCM.
 */
object AudioBus {

    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    private val clients = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()

    fun register(): LinkedBlockingQueue<ByteArray> {
        val queue = LinkedBlockingQueue<ByteArray>(64)
        clients.add(queue)
        return queue
    }

    fun unregister(queue: LinkedBlockingQueue<ByteArray>) {
        clients.remove(queue)
    }

    fun hasClients(): Boolean = clients.isNotEmpty()

    fun publish(chunk: ByteArray) {
        for (queue in clients) {
            if (!queue.offer(chunk)) {
                queue.poll() // drop oldest
                queue.offer(chunk)
            }
        }
    }

    fun clear() {
        clients.clear()
    }
}
