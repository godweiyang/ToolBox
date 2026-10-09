package com.example.videodownloader

import android.app.Application
import android.webkit.WebView

/**
 * 应用入口。
 *
 * enableSlowWholeDocumentDraw 必须在进程内任何 WebView 实例创建之前调用一次：
 * 开启后 WebView.draw(Canvas) 会把整份 HTML 文档（而不只是当前可视区域）绘制到画布，
 * 供「截图分享 / 海报」按元素位置做像素级一致的全页截图，替代页面端 foreignObject 重绘（在
 * Android WebView 上会错位）。代价是 WebView 绘制变慢，对本 app 的使用场景可接受。
 */
class MainApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WebView.enableSlowWholeDocumentDraw()
    }
}
