package com.damir.homecam

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * Fan-out of motion events (their time, epoch ms) to the clients listening on
 * /api/events, the same way [AudioBus] fans out audio.
 */
object MotionEvents {

    private val clients = CopyOnWriteArrayList<LinkedBlockingQueue<Long>>()

    fun register(): LinkedBlockingQueue<Long> =
        LinkedBlockingQueue<Long>(16).also { clients.add(it) }

    fun unregister(queue: LinkedBlockingQueue<Long>) {
        clients.remove(queue)
    }

    fun publish(timeMs: Long) {
        for (queue in clients) {
            if (!queue.offer(timeMs)) {
                queue.poll() // drop oldest
                queue.offer(timeMs)
            }
        }
    }
}
