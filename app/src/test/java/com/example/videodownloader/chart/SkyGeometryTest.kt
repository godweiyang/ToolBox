package com.example.videodownloader.chart

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the polar skyplot mapping: center=zenith, rim=horizon, compass directions. */
class SkyGeometryTest {

    private val cx = 100f
    private val cy = 100f
    private val maxR = 80f
    private val g = SkyGeometry(cx, cy, maxR)

    @Test
    fun zenithMapsToCenter() {
        val p = g.position(azimuthDeg = 123f, elevationDeg = 90f)
        assertEquals(cx, p.sx, 0.001f)
        assertEquals(cy, p.sy, 0.001f)
    }

    @Test
    fun cardinalDirections() {
        // N (0deg) = up
        var p = g.position(0f, 0f)
        assertEquals(cx, p.sx, 0.001f)
        assertEquals(cy - maxR, p.sy, 0.001f)
        // E (90deg) = right
        p = g.position(90f, 0f)
        assertEquals(cx + maxR, p.sx, 0.001f)
        assertEquals(cy, p.sy, 0.001f)
        // S (180deg) = down
        p = g.position(180f, 0f)
        assertEquals(cx, p.sx, 0.001f)
        assertEquals(cy + maxR, p.sy, 0.001f)
        // W (270deg) = left
        p = g.position(270f, 0f)
        assertEquals(cx - maxR, p.sx, 0.001f)
        assertEquals(cy, p.sy, 0.001f)
    }

    @Test
    fun ringsAreThirdsOfMaxR() {
        val rings = g.ringRadii()
        assertEquals(maxR, rings[0], 0.001f)
        assertEquals(maxR * 2f / 3f, rings[1], 0.001f)
        assertEquals(maxR / 3f, rings[2], 0.001f)
    }

    @Test
    fun cn0RadiusMapsFiveToTwelve() {
        assertEquals(5f, g.satRadius(15f), 0.001f)
        assertEquals(12f, g.satRadius(50f), 0.001f)
        assertEquals(9f, g.satRadius(35f), 0.001f)
        // clamped outside range
        assertEquals(5f, g.satRadius(0f), 0.001f)
        assertEquals(12f, g.satRadius(99f), 0.001f)
    }

    @Test
    fun nearestMarkerWithinHitRadius() {
        val pts = listOf(
            SkyPoint(100f, 20f), // N rim
            SkyPoint(180f, 100f) // E rim
        )
        // tap near N marker
        assertEquals(0, g.nearest(pts, 102f, 22f, 48f))
        // tap far from everything -> -1
        assertEquals(-1, g.nearest(pts, 50f, 150f, 48f))
    }
}
