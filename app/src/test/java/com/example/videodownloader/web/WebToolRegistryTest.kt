package com.example.videodownloader.web

import com.example.videodownloader.ToolOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolRegistryTest {

    @Test fun `registry knows the three offline tools`() {
        assertEquals(setOf("lol", "pubg", "fangdai"), WebToolRegistry.ids.toSet())
        assertEquals("lol", WebToolRegistry.byId("lol")?.id)
        assertEquals("pubg", WebToolRegistry.byId("pubg")?.id)
        assertEquals("fangdai", WebToolRegistry.byId("fangdai")?.id)
        assertNull(WebToolRegistry.byId("not_a_tool"))
        assertNull(WebToolRegistry.byId(null))
    }

    @Test fun `ids are unique`() {
        assertEquals(WebToolRegistry.ids.size, WebToolRegistry.ids.toSet().size)
    }

    @Test fun `asset url points at appassets origin`() {
        assertEquals("https://appassets.androidplatform.net/assets/lol/index.html",
            WebToolRegistry.assetUrl("lol"))
        assertEquals("https://appassets.androidplatform.net/assets/fangdai/index.html",
            WebToolRegistry.assetUrl("fangdai"))
    }

    /**
     * 首页排序行为：新增的三个 Web 工具要作为普通工具参与拖拽排序合并——
     * 新工具默认追加末尾，用户排过的顺序被保留，旧 id 丢弃。
     */
    @Test fun `new web tools integrate into persisted order`() {
        // 模拟当前默认首页（既有工具 + 三个 Web 工具）
        val defaultIds = listOf(
            "video_downloader", "qrcode", "video_to_gif", "ninegrid",
            "gifreverse", "decibel", "wifi_signal", "fileshare",
            "metal_detector", "battery_info", "gnss_sky", "photo_frame"
        ) + WebToolRegistry.ids

        // 用户旧版本只排过前几个工具：新版本新增的 web 工具应追加到末尾
        val saved = listOf("qrcode", "video_downloader", "photo_frame")
        val merged = ToolOrder.reconcile(defaultIds, saved)
        assertEquals(
            listOf("qrcode", "video_downloader", "photo_frame",
                "video_to_gif", "ninegrid", "gifreverse", "decibel",
                "wifi_signal", "fileshare", "metal_detector", "battery_info",
                "gnss_sky", "lol", "pubg", "fangdai"),
            merged
        )
    }

    @Test fun `web tools can be reordered by user like any tool`() {
        val defaultIds = listOf("video_downloader", "qrcode") + WebToolRegistry.ids
        // 用户把 fangdai 排到最前
        val saved = listOf("fangdai", "lol", "video_downloader")
        val merged = ToolOrder.reconcile(defaultIds, saved)
        assertEquals(listOf("fangdai", "lol", "video_downloader", "qrcode", "pubg"), merged)
    }

    @Test fun `dropping a web tool removes it from result set`() {
        val defaultIds = listOf("a", "b") + WebToolRegistry.ids
        val merged = ToolOrder.reconcile(defaultIds, listOf("pubg"))
        assertTrue(merged.contains("pubg"))
        assertEquals(defaultIds.toSet(), merged.toSet())
    }
}
