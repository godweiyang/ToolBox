package com.example.videodownloader.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DownloadNamingTest {

    @Test fun `prefers quoted filename from content disposition`() {
        val cd = "attachment; filename=\"report 2024.xlsx\""
        assertEquals("report 2024.xlsx",
            DownloadNaming.resolve("https://x/a/b", cd, null))
    }

    @Test fun `decodes filename star`() {
        val cd = "attachment; filename*=UTF-8''summary.csv"
        assertEquals("summary.csv",
            DownloadNaming.resolve("https://x/y", cd, null))
    }

    @Test fun `falls back to url path segment`() {
        assertEquals("champ.html",
            DownloadNaming.resolve("https://appassets.androidplatform.net/assets/lol/champ.html?x=1", null, null))
    }

    @Test fun `appends extension from mime when missing`() {
        val name = DownloadNaming.resolve("https://example.com/download?id=123", null,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        assertEquals("download.xlsx", name)
    }

    @Test fun `appends image extension`() {
        val name = DownloadNaming.resolve("https://x/blob", "attachment; filename=\"chart\"", "image/png")
        assertEquals("chart.png", name)
    }

    @Test fun `strips path traversal`() {
        val name = DownloadNaming.resolve("https://x/y",
            "attachment; filename=\"../../../../data/private.xlsx\"", null)
        assertEquals("private.xlsx", name)
        assertFalse(name.contains(".."))
        assertFalse(name.contains("/"))
    }

    @Test fun `rejects absolute and backslash paths`() {
        val name = DownloadNaming.resolve("https://x/y",
            "attachment; filename=\"C:\\Windows\\system32\\cmd.exe\"", null)
        assertEquals("cmd.exe", name)
        assertFalse(name.contains("\\"))
        assertFalse(name.contains(":"))
    }

    @Test fun `illegal chars replaced`() {
        val name = DownloadNaming.resolve("https://x/y",
            "attachment; filename=\"a:b*c?d|e.png\"", null)
        assertEquals("a_b_c_d_e.png", name)
    }

    @Test fun `empty falls back to safe default with mime`() {
        val name = DownloadNaming.resolve("https://x/", "", "image/jpeg")
        assertEquals("download.jpg", name)
    }

    @Test fun `mime extension map`() {
        assertEquals("xlsx", DownloadNaming.extensionForMime("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertEquals("png", DownloadNaming.extensionForMime("image/png"))
        assertEquals("jpg", DownloadNaming.extensionForMime("image/jpeg"))
        assertEquals(null, DownloadNaming.extensionForMime(null))
    }
}
