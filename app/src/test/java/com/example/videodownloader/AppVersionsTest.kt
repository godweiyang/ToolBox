package com.example.videodownloader

import org.junit.Assert.*
import org.junit.Test

class AppVersionsTest {
    @Test fun ignoresLeadingVAndTrailingZeros() {
        assertEquals(0, AppVersions.compare("v1.9.25", "1.9.25.0"))
    }

    @Test fun comparesEveryNumericComponent() {
        assertTrue(AppVersions.isNewer("v1.10.0", "1.9.99"))
        assertTrue(AppVersions.isNewer("2.0", "1.99.99"))
        assertFalse(AppVersions.isNewer("1.9.25", "1.9.26"))
    }

    @Test fun prereleaseSuffixDoesNotBreakParsing() {
        assertEquals(0, AppVersions.compare("v1.9.26-beta", "1.9.26"))
    }

    @Test fun blankOrMalformedVersionsAreStable() {
        assertEquals(0, AppVersions.compare("", "invalid"))
        assertTrue(AppVersions.isNewer("1.0.0", "invalid"))
    }

    @Test fun generatedReleaseAssetNameMatchesPublishedConvention() {
        val tag = "v1.9.26"
        val expected = "https://github.com/godweiyang/ToolBox/releases/download/$tag/ToolBox-$tag.apk"
        assertEquals(expected, "https://github.com/godweiyang/ToolBox/releases/download/$tag/ToolBox-$tag.apk")
    }

    @Test fun releaseInfoKeepsBrowserFallbackWhenNoApk() {
        val info = ReleaseInfo("v2.0", "v2.0", "notes", AppUpdater.RELEASES_PAGE, null)
        assertNull(info.apkUrl)
        assertTrue(info.pageUrl.startsWith("https://"))
    }
}
