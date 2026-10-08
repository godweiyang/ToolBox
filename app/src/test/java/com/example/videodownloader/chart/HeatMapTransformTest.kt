package com.example.videodownloader.chart

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the heatmap fit/zoom/round-trip math (formerly inline in HeatMapView). */
class HeatMapTransformTest {

    @Test
    fun singlePointDoesNotDivideByZero() {
        // bbox degenerate (min==max) -> world span floored to 1 unit
        val s = HeatMapTransform.baseScale(0f, 0f, 0f, 0f, 220f, 220f, 80f)
        // avail = 60x60, world = 1x1 -> 60
        assertEquals(60f, s, 0.001f)
    }

    @Test
    fun aspectPreservingFit() {
        // world 100 wide x 50 tall, view 300x300, padding 50 -> avail 200x200
        // scale = min(200/100, 200/50) = min(2, 4) = 2
        val s = HeatMapTransform.baseScale(0f, 0f, 100f, 50f, 300f, 300f, 50f)
        assertEquals(2f, s, 0.001f)
    }

    @Test
    fun zoomClamp() {
        assertEquals(HeatMapTransform.MIN_USER_SCALE, HeatMapTransform.clampUserScale(0.1f), 0.001f)
        assertEquals(HeatMapTransform.MAX_USER_SCALE, HeatMapTransform.clampUserScale(10f), 0.001f)
        assertEquals(2.5f, HeatMapTransform.clampUserScale(2.5f), 0.001f)
    }

    @Test
    fun worldCenterMapsToViewCenter() {
        val (ox, oy) = HeatMapTransform.autoOffset(
            0f, 0f, 100f, 50f, 300f, 300f, 2f
        )
        // world center (50,25) * scale 2 = (100,50); offset = viewCenter - that
        assertEquals(150f - 100f, ox, 0.001f)
        assertEquals(150f - 50f, oy, 0.001f)
    }

    @Test
    fun screenWorldRoundTrip() {
        val offsetX = 70f
        val offsetY = 30f
        val scale = 2.5f
        val wx = 12.345f
        val wy = -8.75f
        assertEquals(wx, HeatMapTransform.worldX(HeatMapTransform.screenX(wx, offsetX, scale), offsetX, scale), 1e-4f)
        assertEquals(wy, HeatMapTransform.worldY(HeatMapTransform.screenY(wy, offsetY, scale), offsetY, scale), 1e-4f)
    }
}
