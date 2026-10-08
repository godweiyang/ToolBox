package com.example.videodownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class GridMetricsTest {

    @Test fun `phones stay on the designed two columns`() {
        assertEquals(2, GridMetrics.spanCountForWidth(320))  // 小屏
        assertEquals(2, GridMetrics.spanCountForWidth(360))  // 主流手机
        assertEquals(2, GridMetrics.spanCountForWidth(411))
    }

    @Test fun `wide screens widen to three columns`() {
        assertEquals(3, GridMetrics.spanCountForWidth(600))  // 阈值点
        assertEquals(3, GridMetrics.spanCountForWidth(800))
        assertEquals(3, GridMetrics.spanCountForWidth(1000))
    }

    @Test fun `just below threshold remains two columns`() {
        assertEquals(2, GridMetrics.spanCountForWidth(599))
    }
}
