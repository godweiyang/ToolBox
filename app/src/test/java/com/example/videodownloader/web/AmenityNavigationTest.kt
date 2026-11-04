package com.example.videodownloader.web

import org.junit.Assert.*
import org.junit.Test

class AmenityNavigationTest {
    @Test fun exactOriginsOnly() {
        assertTrue(AmenityNavigation.isBeike("https://sh.ke.com/xiaoqu/5011000014068/"))
        for (url in listOf("https://sh.ke.com.evil.test/", "http://sh.ke.com/", "https://evil@sh.ke.com/", "https://sh.ke.com:444/")) {
            assertFalse(url, AmenityNavigation.isBeike(url))
        }
        assertFalse(AmenityNavigation.isProfile("https://sh.ke.com/passport/login"))
        assertFalse(AmenityNavigation.isProfile("https://sh.ke.com/xiaoqu/123/?url=evil"))
        assertTrue(AmenityNavigation.isProfile("https://sh.ke.com/ershoufang/123.html"))
    }
    @Test fun safeAssetsAndLoopback() {
        assertTrue(AmenityNavigation.isScore(AmenityNavigation.SCORE_URL))
        assertEquals("amenity/index.html", AmenityNavigation.assetPath(AmenityNavigation.SCORE_URL))
        assertNull(AmenityNavigation.assetPath("https://appassets.androidplatform.net/assets/amenity/%2e%2e/secret"))
        assertFalse(AmenityNavigation.isScore("https://evil.test/assets/amenity/index.html"))
        assertTrue(AmenityNavigation.isLocal("http://127.0.0.1:17532/api/health"))
        assertFalse(AmenityNavigation.isLocal("http://127.0.0.1:17530/api/health"))
        assertEquals("310000", AmenityNavigation.city(AmenityNavigation.MAP_URL))
    }
}
