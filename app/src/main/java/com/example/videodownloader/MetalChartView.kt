package com.example.videodownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 实时波形图（通用）：磁场、分贝、加速度等均可复用。
 *
 * 横轴：时间（最近的样本画在右边，更早的画在左边，自动滚动）
 * 纵轴：自动按 [maxValue] 缩放到画布高度
 *
 * 数据通过 [addPoint] 追加，超出 [MAX_POINTS] 时丢弃最旧的。
 * 可选阈值线：超过阈值的点用红色高亮（[showThreshold] = true 时启用）。
 */
class MetalChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        /** 最多保留多少个采样点（约对应 20 秒，50ms 一个点） */
        private const val MAX_POINTS = 400
    }

    private val points = ArrayList<Float>(MAX_POINTS)
    private var maxValue = 100f
    private var minValue = 0f
    private var threshold = 60f
    /** 是否绘制阈值线 + 超阈值变红（分贝仪等不需要阈值时设为 false） */
    private var showThreshold = true
    /** 主色（默认磁场蓝），分贝仪可设为紫色 */
    private var mainColor = 0xFF0288D1.toInt()
    private var fillColor = 0x330288D1

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = mainColor
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF44336.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textSize = 24f
    }

    /** 配置外观：主色 / 是否显示阈值线 / 初始纵轴上下限 */
    fun configure(
        color: Int,
        showThreshold: Boolean,
        initMax: Float = 100f,
        initMin: Float = 0f
    ) {
        mainColor = color
        fillColor = (color and 0x00FFFFFF) or 0x33000000.toInt()
        this.showThreshold = showThreshold
        maxValue = initMax
        minValue = initMin
        linePaint.color = mainColor
        fillPaint.color = fillColor
        invalidate()
    }

    /** 追加一个采样点 */
    fun addPoint(value: Float) {
        points.add(value)
        while (points.size > MAX_POINTS) points.removeAt(0)
        // 自动放大纵轴范围
        if (value > maxValue) {
            maxValue = value * 1.2f
        }
        if (value < minValue) {
            minValue = value * 0.8f
        }
        invalidate()
    }

    /** 设置阈值（用于画阈值线） */
    fun setThreshold(t: Float) {
        threshold = t
        invalidate()
    }

    /** 重置所有数据 */
    fun reset() {
        points.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. 网格：横向 4 条
        for (i in 1 until 4) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // 2. 阈值线（按 maxValue/minValue 缩放）
        val range = maxValue - minValue
        if (range > 0f && showThreshold && threshold in minValue..maxValue) {
            val ty = h - ((threshold - minValue) / range) * h
            canvas.drawLine(0f, ty, w, ty, thresholdPaint)
            canvas.drawText("%.0f".format(threshold), 8f, ty - 4f, textPaint)
        }

        // 3. 数据曲线
        if (points.size < 2) return
        if (range <= 0f) return
        val stepX = w / (MAX_POINTS - 1)
        val linePath = Path()
        val fillPath = Path()
        val startIdx = MAX_POINTS - points.size  // 让最新点贴近右边
        var first = true
        fillPath.moveTo(0f, h)
        for (i in points.indices) {
            val x = (startIdx + i) * stepX
            val v = points[i].coerceIn(minValue, maxValue)
            val y = h - ((v - minValue) / range) * h
            if (first) {
                linePath.moveTo(x, y)
                fillPath.lineTo(x, y)
                first = false
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        // 闭合填充路径
        val lastX = (startIdx + points.size - 1) * stepX
        fillPath.lineTo(lastX, h)
        fillPath.lineTo(0f, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        // 超阈值部分用红色画（仅在 showThreshold 模式下）
        val overThreshold = showThreshold && (points.maxOrNull()?.let { it > threshold } ?: false)
        linePaint.color = if (overThreshold) 0xFFF44336.toInt() else mainColor
        canvas.drawPath(linePath, linePaint)
    }
}
