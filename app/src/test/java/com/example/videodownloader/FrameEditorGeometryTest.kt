package com.example.videodownloader

import org.junit.Assert.*
import org.junit.Test

/** 校验 PhotoFrame 编辑器的 app 区域几何与参考截图一致。 */
class FrameEditorGeometryTest {

    private fun pxToDpMatches(px: Int, dp: Int, toleranceDp: Int = 2) {
        val expected = FrameEditorGeometry.dp(px)
        assertEquals("px=$px -> dp", expected.toDouble(), dp.toDouble(), toleranceDp.toDouble())
    }

    @Test fun canvasMatchesReference() {
        assertEquals(1260, FrameEditorGeometry.DESIGN_WIDTH_PX)
        assertEquals(2800, FrameEditorGeometry.DESIGN_HEIGHT_PX)
        // 设计网格：360dp 宽
        assertEquals(360.0, FrameEditorGeometry.DESIGN_WIDTH_PX / FrameEditorGeometry.DENSITY.toDouble(), 0.5)
    }

    @Test fun appContentStartsBelowStatusBar() {
        assertTrue(FrameEditorGeometry.APP_TOP_PX > FrameEditorGeometry.STATUS_BAR_BOTTOM_PX)
    }

    @Test fun cardMarginsMapToChosenDp() {
        pxToDpMatches(FrameEditorGeometry.CARD_RECT_PX[0], FrameEditorGeometry.Dp.CARD_MARGIN)
        // 卡片宽 1042px -> 298dp
        pxToDpMatches(FrameEditorGeometry.CARD_RECT_PX[2] - FrameEditorGeometry.CARD_RECT_PX[0], 298)
    }

    @Test fun trayAndThumbSizesMapToChosenDp() {
        pxToDpMatches(FrameEditorGeometry.TRAY_RECT_PX[3] - FrameEditorGeometry.TRAY_RECT_PX[1], FrameEditorGeometry.Dp.TRAY_H)
        pxToDpMatches(FrameEditorGeometry.THUMB_SIZE_PX, FrameEditorGeometry.Dp.THUMB)
        pxToDpMatches(FrameEditorGeometry.ADD_BUTTON_RECT_PX[2] - FrameEditorGeometry.ADD_BUTTON_RECT_PX[0], FrameEditorGeometry.Dp.ADD_BTN)
    }

    @Test fun ratioPanelHasFourEqualColumns() {
        assertEquals(4, FrameEditorGeometry.RADIO_CENTERS_X_PX.size)
        // 四列等距
        val centers = FrameEditorGeometry.RADIO_CENTERS_X_PX
        for (i in 1 until centers.size) {
            assertEquals(250.0, (centers[i] - centers[i - 1]).toDouble(), 1.0)
        }
        pxToDpMatches(FrameEditorGeometry.RADIO_DIAMETER_PX, FrameEditorGeometry.Dp.RADIO)
    }

    @Test fun sheetTopMeetsCardBottom() {
        // 卡片底部与深色面板顶部相接（中心处）
        assertEquals(FrameEditorGeometry.CARD_RECT_PX[3], FrameEditorGeometry.SHEET_TOP_PX)
    }

    @Test fun tabUnderlineSitsBelowSelectedTab() {
        val u = FrameEditorGeometry.TAB_UNDERLINE_RECT_PX
        assertTrue(u[2] - u[0] > 0)
        assertTrue(u[1] > FrameEditorGeometry.TAB_TEXT_BOTTOM_PX)
    }

    @Test fun weightsSplitPreviewAndSheet() {
        assertEquals(1.0, FrameEditorGeometry.Dp.PREVIEW_WEIGHT + FrameEditorGeometry.Dp.SHEET_WEIGHT, 0.01)
    }
}
