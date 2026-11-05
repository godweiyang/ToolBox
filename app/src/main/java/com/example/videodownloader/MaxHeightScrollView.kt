package com.example.videodownloader

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 真正支持 android:maxHeight 的 ScrollView。
 *
 * 原生 ScrollView 重写了 onMeasure，不读取 View 的 maxHeight 属性，
 * 导致布局里写 android:maxHeight 完全无效；更新日志较长时弹窗会被
 * 撑到超出屏幕，底部的下载按钮被挤出可视区域。这里在 onMeasure 时
 * 主动把高度规格钳制在 maxHeight 以内。
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ScrollView(context, attrs, defStyle) {

    private var maxHeightPx: Int = 0

    init {
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
            maxHeightPx = ta.getDimensionPixelSize(0, 0)
            ta.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var spec = heightMeasureSpec
        if (maxHeightPx > 0) {
            val size = minOf(MeasureSpec.getSize(heightMeasureSpec), maxHeightPx)
            spec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}
