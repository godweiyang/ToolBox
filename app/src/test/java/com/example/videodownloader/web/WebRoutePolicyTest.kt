package com.example.videodownloader.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRoutePolicyTest {

    @Test fun `appassets origin stays in app including cross tool nav`() {
        val home = "https://appassets.androidplatform.net/assets/lol/index.html"
        val cross = "https://appassets.androidplatform.net/assets/pubg/index.html"
        val sub = "https://appassets.androidplatform.net/assets/fangdai/app.js"
        assertEquals(WebRoutePolicy.Decision.ALLOW_IN_APP, WebRoutePolicy.decide(home))
        assertEquals(WebRoutePolicy.Decision.ALLOW_IN_APP, WebRoutePolicy.decide(cross))
        assertEquals(WebRoutePolicy.Decision.ALLOW_IN_APP, WebRoutePolicy.decide(sub))
        assertTrue(WebRoutePolicy.isAssetOrigin(home))
        assertTrue(WebRoutePolicy.isAssetOrigin(cross))
    }

    @Test fun `loopback http is allowed in app`() {
        assertEquals(WebRoutePolicy.Decision.ALLOW_IN_APP,
            WebRoutePolicy.decide("http://127.0.0.1:8080/page"))
        assertEquals(WebRoutePolicy.Decision.ALLOW_IN_APP,
            WebRoutePolicy.decide("http://localhost:9000/"))
        assertTrue(WebRoutePolicy.isLoopback("http://127.0.0.1:1/x"))
        assertTrue(WebRoutePolicy.isLoopback("http://localhost/"))
        assertFalse(WebRoutePolicy.isLoopback("http://8.8.8.8/"))
    }

    @Test fun `external https opens in browser not webview`() {
        assertEquals(WebRoutePolicy.Decision.OPEN_EXTERNAL_BROWSER,
            WebRoutePolicy.decide("https://example.com/foo"))
        assertEquals(WebRoutePolicy.Decision.OPEN_EXTERNAL_BROWSER,
            WebRoutePolicy.decide("https://www.google.com/search?q=x"))
        assertFalse(WebRoutePolicy.isAssetOrigin("https://example.com/"))
    }

    @Test fun `non-loopback http opens in browser`() {
        assertEquals(WebRoutePolicy.Decision.OPEN_EXTERNAL_BROWSER,
            WebRoutePolicy.decide("http://example.com/file.xlsx"))
    }

    @Test fun `file url is blocked`() {
        assertEquals(WebRoutePolicy.Decision.BLOCK,
            WebRoutePolicy.decide("file:///android_asset/lol/index.html"))
        assertEquals(WebRoutePolicy.Decision.BLOCK,
            WebRoutePolicy.decide("file:///sdcard/private.txt"))
    }

    @Test fun `blank and about handled`() {
        assertEquals(WebRoutePolicy.Decision.BLOCK, WebRoutePolicy.decide(null))
        assertEquals(WebRoutePolicy.Decision.BLOCK, WebRoutePolicy.decide("   "))
        assertEquals(WebRoutePolicy.Decision.ALLOW_IN_APP, WebRoutePolicy.decide("about:blank"))
    }

    @Test fun `mailto and tel go external`() {
        assertEquals(WebRoutePolicy.Decision.OPEN_EXTERNAL_BROWSER,
            WebRoutePolicy.decide("mailto:a@b.com"))
        assertEquals(WebRoutePolicy.Decision.OPEN_EXTERNAL_BROWSER,
            WebRoutePolicy.decide("tel:+86100"))
    }

    @Test fun `asset alias maps friendly tool paths to assets`() {
        assertEquals("lol/index.html", WebRoutePolicy.resolveAssetPath("/assets/lol/index.html"))
        assertEquals("lol/index.html", WebRoutePolicy.resolveAssetPath("/lol/"))
        assertEquals("lol/index.html", WebRoutePolicy.resolveAssetPath("/lol"))
        assertEquals("pubg/arena.html", WebRoutePolicy.resolveAssetPath("/pubg/arena.html"))
        assertEquals("fangdai/index.html", WebRoutePolicy.resolveAssetPath("/fangdai/"))
        assertEquals("fangdai/js/app.js", WebRoutePolicy.resolveAssetPath("/fangdai/js/app.js"))
    }

    @Test fun `unknown top level alias is not resolved`() {
        assertNull(WebRoutePolicy.resolveAssetPath("/licai/"))
        assertNull(WebRoutePolicy.resolveAssetPath("/random/x.html"))
        assertNull(WebRoutePolicy.resolveAssetPath(null))
    }

    @Test fun `only loopback http subresources pass`() {
        // LOL 本地服务回环：放行
        assertFalse(WebRoutePolicy.isBlockedHttpSubResource("http://127.0.0.1:17530/api/foo"))
        assertFalse(WebRoutePolicy.isBlockedHttpSubResource("http://localhost:17530/"))
        // 任意其它明文 http：拦截
        assertTrue(WebRoutePolicy.isBlockedHttpSubResource("http://evil.com/x.png"))
        // https 与 file 不属于明文 http 子资源拦截
        assertFalse(WebRoutePolicy.isBlockedHttpSubResource("https://cdn.example.com/x.js"))
        assertFalse(WebRoutePolicy.isBlockedHttpSubResource("file:///x"))
    }

    @Test fun `custom scheme lolzjcx is external and graceful`() {
        assertTrue(WebRoutePolicy.isExternalScheme("lolzjcx://start"))
        assertTrue(WebRoutePolicy.isExternalScheme("lolzjcxdev://start"))
        assertEquals(WebRoutePolicy.Decision.OPEN_EXTERNAL_BROWSER,
            WebRoutePolicy.decide("lolzjcx://start"))
        assertFalse(WebRoutePolicy.isExternalScheme("https://appassets.androidplatform.net/lol/"))
    }
}
