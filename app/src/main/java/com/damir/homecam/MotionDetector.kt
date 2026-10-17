package com.damir.homecam

import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * Lightweight motion detector. It samples the luminance (Y) plane of each
 * camera frame onto a small fixed grid and compares it to the previous frame.
 * The mean absolute difference, normalised to 0..255, is the motion "score".
 * Working on luma avoids any JPEG/bitmap decoding, so it is cheap enough to run
 * on every analysed frame.
 */
class MotionDetector {

    private var previous: IntArray? = null

    /**
     * @return true when the frame-to-frame change exceeds the threshold implied
     *         by [sensitivity] (1 = least sensitive, 100 = most sensitive).
     */
    fun process(image: ImageProxy, sensitivity: Int): Boolean {
        val grid = sample(image)
        val prev = previous
        previous = grid
        if (prev == null || prev.size != grid.size) return false

        var sum = 0L
        for (i in grid.indices) {
            sum += abs(grid[i] - prev[i])
        }
        val score = sum.toDouble() / grid.size // 0..255

        // Map sensitivity 1..100 to a difference threshold ~30 (coarse) .. 2 (fine).
        val threshold = 30.0 - (sensitivity.coerceIn(1, 100) - 1) * (28.0 / 99.0)
        return score >= threshold
    }

    fun reset() {
        previous = null
    }

    private fun sample(image: ImageProxy): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        val out = IntArray(GRID_W * GRID_H)
        var idx = 0
        for (gy in 0 until GRID_H) {
            val y = (gy * height) / GRID_H
            val rowBase = y * rowStride
            for (gx in 0 until GRID_W) {
                val x = (gx * width) / GRID_W
                val pos = rowBase + x * pixelStride
                out[idx++] = if (pos < buffer.limit()) buffer.get(pos).toInt() and 0xFF else 0
            }
        }
        return out
    }

    companion object {
        private const val GRID_W = 32
        private const val GRID_H = 24
    }
}
