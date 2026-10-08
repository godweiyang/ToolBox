package com.example.videodownloader.chart

import kotlin.math.max
import kotlin.math.min

/**
 * Pure world<->screen transform math for the WiFi heatmap (formerly inlined in HeatMapView).
 *
 * Invariants preserved:
 *  - user zoom is clamped to [0.3, 8].
 *  - auto-fit keeps aspect: baseScale = min(availW/worldW, availH/worldH),
 *    world spans floored at 1 unit (single-point divide guard).
 *  - world bbox center maps to view center.
 *  - screen = offset + world*scale; world = (screen - offset)/scale are exact inverses.
 */
object HeatMapTransform {

    const val MIN_USER_SCALE = 0.3f
    const val MAX_USER_SCALE = 8f
    const val DEFAULT_PADDING = 80f
    const val GRID_MIN_PX = 20f

    fun clampUserScale(scale: Float): Float = scale.coerceIn(MIN_USER_SCALE, MAX_USER_SCALE)

    fun baseScale(
        minX: Float, minY: Float, maxX: Float, maxY: Float,
        width: Float, height: Float, padding: Float = DEFAULT_PADDING
    ): Float {
        if (width <= 0f || height <= 0f) return 1f
        val worldW = max(maxX - minX, 1f)
        val worldH = max(maxY - minY, 1f)
        val availW = width - padding * 2
        val availH = height - padding * 2
        if (availW <= 0f || availH <= 0f) return 1f
        return min(availW / worldW, availH / worldH)
    }

    /** offset pair that places the world bbox center at the view center. */
    fun autoOffset(
        minX: Float, minY: Float, maxX: Float, maxY: Float,
        width: Float, height: Float, scale: Float
    ): Pair<Float, Float> {
        val worldCenterX = (minX + maxX) / 2f
        val worldCenterY = (minY + maxY) / 2f
        return width / 2f - worldCenterX * scale to height / 2f - worldCenterY * scale
    }

    fun screenX(worldX: Float, offsetX: Float, scale: Float): Float = offsetX + worldX * scale
    fun screenY(worldY: Float, offsetY: Float, scale: Float): Float = offsetY + worldY * scale
    fun worldX(screenX: Float, offsetX: Float, scale: Float): Float = (screenX - offsetX) / scale
    fun worldY(screenY: Float, offsetY: Float, scale: Float): Float = (screenY - offsetY) / scale
}
