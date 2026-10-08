package com.example.videodownloader.chart

/**
 * Pure geometry for the rolling line chart (formerly inlined in MetalChartView.onDraw).
 *
 * Invariants preserved:
 *  - stepX = width / (MAX_POINTS - 1)
 *  - newest point is pinned to the right edge: startIndex = MAX_POINTS - pointCount,
 *    so xAt(last) == width.
 *  - value -> y with 0 at the bottom: y = height - ((v - min)/(max - min)) * height,
 *    values clamped to [min, max].
 *  - auto-zoom: incoming above max stretches max by 1.2, below min shrinks min by 0.8.
 *
 * Framework-free for JUnit.
 */
class LineChartGeometry(
    private val width: Float,
    private val height: Float,
    private val pointCount: Int,
    private val min: Float,
    private val max: Float,
    private val maxPoints: Int = MAX_POINTS
) {
    val range: Float = max - min
    val stepX: Float = if (maxPoints > 1) width / (maxPoints - 1) else width.toFloat()
    val startIndex: Int = maxPoints - pointCount

    /** Screen x for the i-th buffered sample (0-based). */
    fun xAt(i: Int): Float = (startIndex + i) * stepX

    /** Screen y for a value; 0 (top) at [max], height (bottom) at [min]. */
    fun yAt(value: Float): Float {
        if (range <= 0f) return height
        val v = value.coerceIn(min, max)
        return height - ((v - min) / range) * height
    }

    /** Screen y for a threshold value. */
    fun thresholdY(threshold: Float): Float =
        height - ((threshold - min) / range) * height

    companion object {
        const val MAX_POINTS = 400

        fun zoomMax(current: Float, incoming: Float): Float =
            if (incoming > current) incoming * 1.2f else current

        fun zoomMin(current: Float, incoming: Float): Float =
            if (incoming < current) incoming * 0.8f else current
    }
}
