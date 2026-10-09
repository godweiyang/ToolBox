package com.example.videodownloader.web

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView

/**
 * WebView 全文档截图：配合 Application 中开启的 WebView.enableSlowWholeDocumentDraw，
 * 直接把真实渲染结果绘制到 Bitmap——所见即所得，不会出现 html-to-image（foreignObject
 * 序列化）在 Android WebView 上的排版错位。
 *
 * 页面把目标元素在「文档坐标系」（含滚动偏移）下的 CSS 像素矩形传过来，
 * 这里通过画布平移只截取该元素区域，避免生成整张页面的超大 Bitmap。
 */
object WebViewCapture {

    // 长图最长边（px）上限，按 1080 宽估算内存约 ≤39MB；失败逐级缩小重试
    private val MAX_EDGES = intArrayOf(9000, 7000, 5000, 3600)

    /**
     * @param cssX 元素左边缘相对文档的 x（CSS px）
     * @param cssY 元素上边缘相对文档的 y（CSS px）
     * @param cssW 元素宽（CSS px）
     * @param cssH 元素高（CSS px）
     * @return 元素截图；始终失败返回 null
     */
    fun captureElement(
        web: WebView,
        cssX: Float,
        cssY: Float,
        cssW: Float,
        cssH: Float
    ): Bitmap? {
        if (cssW <= 0f || cssH <= 0f || web.width <= 0) return null
        val density = web.resources.displayMetrics.density
        val eWpx = cssW * density
        val eHpx = cssH * density

        val savedScrollY = web.scrollY
        web.scrollTo(0, 0)
        try {
            for (maxEdge in MAX_EDGES) {
                val scale = minOf(1f, maxEdge / eWpx, maxEdge / eHpx)
                val bw = maxOf(1, (eWpx * scale).toInt())
                val bh = maxOf(1, (eHpx * scale).toInt())
                val bmp = try {
                    Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                } catch (oom: OutOfMemoryError) {
                    null
                } ?: continue
                val canvas = Canvas(bmp)
                canvas.scale(scale, scale)
                // 把文档坐标系平移，使目标元素落在 (0,0)
                canvas.translate(-cssX * density, -cssY * density)
                val ok = try {
                    web.draw(canvas)
                    true
                } catch (oom: OutOfMemoryError) {
                    bmp.recycle()
                    false
                }
                if (ok) return bmp
            }
        } finally {
            web.scrollTo(0, savedScrollY)
        }
        return null
    }
}
