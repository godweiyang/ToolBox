package com.example.videodownloader.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the rolling line-chart coordinate mapping (formerly inline in MetalChartView). */
class LineChartGeometryTest {

    private val width = 400f
    private val height = 200f
    private val min = 0f
    private val max = 100f

    private fun geo(pointCount: Int) =
        LineChartGeometry(width, height, pointCount, min, max)

    @Test
    fun stepX_spansFullWidth() {
        val g = geo(LineChartGeometry.MAX_POINTS)
        // 400 samples: first at 0, last at width
        assertEquals(0f, g.xAt(0), 0.001f)
        assertEquals(width, g.xAt(LineChartGeometry.MAX_POINTS - 1), 0.5f)
    }

    @Test
    fun newestPointPinnedToRightEdge() {
        // Only 100 buffered points -> left padding, newest still lands on right edge.
        val g = geo(100)
        assertEquals(LineChartGeometry.MAX_POINTS - 100, g.startIndex)
        assertEquals(width, g.xAt(99), 0.5f)
    }

    @Test
    fun yAxisTopBottomAndMiddle() {
        val g = geo(10)
        assertEquals(0f, g.yAt(max), 0.001f)   // max value at top
        assertEquals(height, g.yAt(min), 0.001f) // min value at bottom
        assertEquals(height / 2f, g.yAt(50f), 0.001f)
    }

    @Test
    fun valuesClampedOutsideRange() {
        val g = geo(10)
        assertEquals(g.yAt(max), g.yAt(500f), 0.001f)  // above max -> top
        assertEquals(g.yAt(min), g.yAt(-50f), 0.001f)  // below min -> bottom
    }

    @Test
    fun thresholdY_matchesManualFormula() {
        val g = geo(10)
        val t = 25f
        // h - ((t-min)/(max-min))*h
        val expected = height - ((t - min) / (max - min)) * height
        assertEquals(expected, g.thresholdY(t), 0.001f)
    }

    @Test
    fun autoZoomFactors() {
        assertEquals(120f * 1.2f, LineChartGeometry.zoomMax(100f, 120f), 0.001f)
        assertEquals(100f, LineChartGeometry.zoomMax(100f, 80f), 0.001f)
        assertEquals(-10f * 0.8f, LineChartGeometry.zoomMin(0f, -10f), 0.001f)
        assertEquals(0f, LineChartGeometry.zoomMin(0f, 10f), 0.001f)
    }

    @Test
    fun degenerateRange_doesNotDivideByZero() {
        val g = LineChartGeometry(width, height, 5, 50f, 50f)
        assertTrue(g.range <= 0f)
        // returns a finite y instead of NaN/Inf
        assertEquals(height, g.yAt(50f), 0.001f)
    }
}
