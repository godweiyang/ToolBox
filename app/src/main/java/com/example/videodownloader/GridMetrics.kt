package com.example.videodownloader

/**
 * 响应式网格列数的纯逻辑。主页工具网格按屏幕宽度决定列数：
 *  手机（<600dp 宽）固定 2 列，保持原有设计；
 *  宽屏（>=600dp，如平板/分屏）放到 3 列，避免卡片过宽。
 *
 * 纯函数、不依赖 Android，可直接在 src/test 下单测。
 */
object GridMetrics {

    const val PHONE_COLUMNS: Int = 2
    const val WIDE_COLUMNS: Int = 3

    /** 宽度达到该阈值（dp）时切换到多列布局。 */
    const val WIDE_SCREEN_DP: Int = 600

    fun spanCountForWidth(screenWidthDp: Int): Int =
        if (screenWidthDp >= WIDE_SCREEN_DP) WIDE_COLUMNS else PHONE_COLUMNS
}
