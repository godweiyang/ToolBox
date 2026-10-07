package com.example.videodownloader

/**
 * PhotoFrame 编辑器 app 区域的确定性几何规格。
 *
 * 参考截图为 1260x2800（RGB）。本对象把参考图上量得的像素矩形归一化到
 * 360dp x 800dp 的设计网格（密度 = 1260/360 = 3.5），与布局 XML 中使用的 dp
 * 取值一一对应。纯 JVM 可测，不依赖 Android 框架。
 *
 * 注意：系统状态栏（y < 106）不属于 app 控制区，不在此规格内。
 */
object FrameEditorGeometry {
    const val DESIGN_WIDTH_PX = 1260
    const val DESIGN_HEIGHT_PX = 2800
    const val STATUS_BAR_BOTTOM_PX = 105

    /** 设计网格密度：px / dp。 */
    const val DENSITY = 3.5f

    /** app 内容从状态栏下方开始。 */
    const val APP_TOP_PX = 106

    /** 顶部橙色背景/工具栏。 */
    const val TOOLBAR_TOP_PX = APP_TOP_PX
    const val TOOLBAR_BOTTOM_PX = 280

    /** 预览卡片（照片位图的容器，左右对称留白）。 */
    val CARD_RECT_PX = intArrayOf(108, 352, 1150, 1477)

    /** 底部深色面板。 */
    const val SHEET_TOP_PX = 1477
    const val SHEET_RADIUS_PX = 40
    val HANDLE_RECT_PX = intArrayOf(577, 1522, 681, 1536)

    /** 缩略图托盘。 */
    val TRAY_RECT_PX = intArrayOf(35, 1603, 1224, 1818)
    const val TRAY_RADIUS_PX = 24
    const val THUMB_SIZE_PX = 160
    val ADD_BUTTON_RECT_PX = intArrayOf(1047, 1636, 1196, 1788)

    /** 比例选项面板（四列）。 */
    val RATIO_PANEL_RECT_PX = intArrayOf(96, 2016, 1164, 2435)
    const val RATIO_PANEL_RADIUS_PX = 41
    const val RADIO_DIAMETER_PX = 72
    val RADIO_CENTERS_X_PX = intArrayOf(254, 504, 754, 1005)

    /** 底部横向标签。 */
    const val TAB_TEXT_TOP_PX = 2560
    const val TAB_TEXT_BOTTOM_PX = 2602
    val TAB_UNDERLINE_RECT_PX = intArrayOf(57, 2658, 167, 2666)

    /** 关键间距（px）。 */
    const val TOOLBAR_TO_CARD_GAP_PX = 77
    const val SHEET_TOP_TO_HANDLE_PX = 45
    const val HANDLE_TO_TRAY_PX = 67
    const val TRAY_TO_RATIO_PANEL_PX = 198
    const val RATIO_PANEL_TO_TABS_PX = 125

    /** px -> dp，四舍五入到整数 dp。 */
    fun dp(px: Int): Int = Math.round(px / DENSITY)

    /** 布局中实际采用的 dp 取值（与 activity_photo_frame.xml 对齐）。 */
    object Dp {
        const val CARD_MARGIN = 31          // 108px / 3.5 ≈ 31
        const val PREVIEW_WEIGHT = 0.51
        const val SHEET_WEIGHT = 0.49
        const val HANDLE_W = 30             // 104px / 3.5 ≈ 30
        const val HANDLE_H = 4              // 14px / 3.5 = 4
        const val TRAY_H = 61               // 215px / 3.5 ≈ 61
        const val TRAY_MARGIN = 10          // 35px / 3.5 = 10
        const val THUMB = 46                // 160px / 3.5 ≈ 46
        const val ADD_BTN = 43              // 149px / 3.5 ≈ 43
        const val RATIO_PANEL_H = 120       // 419px / 3.5 ≈ 120
        const val RATIO_PANEL_MARGIN = 27   // 96px / 3.5 ≈ 27
        const val RADIO = 20                // 72px / 3.5 ≈ 20
        const val DELETE_BADGE = 18        // 57px / 3.5 ≈ 16 (18 含容差)
    }
}
