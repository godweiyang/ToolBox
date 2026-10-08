package com.example.videodownloader.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the WiFi RSSI->color ramp endpoints and level buckets. */
class RssiScaleTest {

    @Test
    fun endpoints() {
        // -90 dBm = red 0xE53935 (229,57,53)
        assertEquals(229, Argb.red(RssiScale.color(-90)).toLong())
        assertEquals(57, Argb.green(RssiScale.color(-90)).toLong())
        assertEquals(53, Argb.blue(RssiScale.color(-90)).toLong())
        // -60 dBm = yellow 0xFDD835 (253,216,53) split point
        assertEquals(253, Argb.red(RssiScale.color(-60)).toLong())
        assertEquals(216, Argb.green(RssiScale.color(-60)).toLong())
        assertEquals(53, Argb.blue(RssiScale.color(-60)).toLong())
        // -30 dBm = green 0x43A047 (67,160,71)
        assertEquals(67, Argb.red(RssiScale.color(-30)).toLong())
        assertEquals(160, Argb.green(RssiScale.color(-30)).toLong())
        assertEquals(71, Argb.blue(RssiScale.color(-30)).toLong())
    }

    @Test
    fun outOfRangeClampsToEndpoints() {
        assertEquals(RssiScale.color(-90), RssiScale.color(-120))
        assertEquals(RssiScale.color(-30), RssiScale.color(-10))
    }

    @Test
    fun rampIsMonotonic() {
        // Green channel climbs from red side to green side.
        val g1 = Argb.green(RssiScale.color(-80))
        val g2 = Argb.green(RssiScale.color(-40))
        assertTrue("green should increase with signal", g2 > g1)
    }

    @Test
    fun levelBuckets() {
        assertEquals("极佳", RssiScale.level(-55))
        assertEquals("良好", RssiScale.level(-65))
        assertEquals("一般", RssiScale.level(-75))
        assertEquals("较弱", RssiScale.level(-85))
        assertEquals("很差", RssiScale.level(-100))
    }
}
