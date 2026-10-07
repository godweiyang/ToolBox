package com.example.videodownloader

import kotlin.math.ceil
import kotlin.math.exp

/** Shared, resolution-independent calibration. Sigma means Gaussian standard deviation. */
internal object FrameStyle {
    const val GLOW_WIDTH = 400
    const val GLOW_SIGMA = 22.75f
    const val SHADOW_WIDTH = 480
    const val SHADOW_SIGMA_RATIO = 39.8f / 1440f
    const val SHADOW_OPACITY = 1.02f
    const val NIKON_HEIGHT_RATIO = 25f / 1442f
    const val NIKON_ASPECT = 99f / 25f
    const val BRAND_MODEL_GAP_RATIO = 25f / 1442f
    const val FOOTER_BOTTOM_RATIO = 24f / 1442f
    const val FOOTER_LINE_GAP_RATIO = 16f / 1442f
}

/**
 * Normalized separable Gaussian convolution, with replicated edges.
 * Float intermediates avoid the cumulative rounding/banding of repeated integer box blur.
 * Each output column samples its own column; neither side borrows the opposite edge.
 */
internal object GaussianBlur {
    fun kernel(sigma: Float): FloatArray {
        require(sigma.isFinite() && sigma > 0f)
        val radius = ceil(sigma * 3.0).toInt()
        val weights = FloatArray(radius * 2 + 1) { i ->
            val x = (i - radius).toDouble()
            exp(-x * x / (2.0 * sigma * sigma)).toFloat()
        }
        val sum = weights.sum()
        for (i in weights.indices) weights[i] /= sum
        return weights
    }

    fun blur(values: FloatArray, width: Int, height: Int, sigma: Float) {
        require(width > 0 && height > 0 && values.size.toLong() == width.toLong() * height)
        require(sigma.isFinite() && sigma >= 0f)
        if (sigma == 0f) return
        val weights = kernel(sigma)
        val radius = weights.size / 2
        val temp = FloatArray(values.size)
        val row = FloatArray(width + radius * 2)
        for (y in 0 until height) {
            val offset = y * width
            row.fill(values[offset], 0, radius)
            values.copyInto(row, radius, offset, offset + width)
            row.fill(values[offset + width - 1], radius + width, row.size)
            for (x in 0 until width) {
                var sum = 0f
                for (k in weights.indices) sum += row[x + k] * weights[k]
                temp[offset + x] = sum
            }
        }
        val column = FloatArray(height + radius * 2)
        for (x in 0 until width) {
            column.fill(temp[x], 0, radius)
            for (y in 0 until height) column[y + radius] = temp[y * width + x]
            column.fill(temp[(height - 1) * width + x], radius + height, column.size)
            for (y in 0 until height) {
                var sum = 0f
                for (k in weights.indices) sum += column[y + k] * weights[k]
                values[y * width + x] = sum
            }
        }
    }
}
