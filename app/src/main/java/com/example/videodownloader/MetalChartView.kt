package com.example.videodownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.videodownloader.chart.Argb
import com.example.videodownloader.chart.LineChartGeometry

/**
 * 实时波形图（通用）：磁场、分贝、电流/电压/功率/温度、RSSI、载噪比等均可复用。
 *
 * 横轴：时间（最新样本在右侧，更早的在左侧，自动滚动）
 * 纵轴：按 [maxValue]/[minValue] 自动缩放到画布高度
 *
 * 数据通过 [addPoint] 追加，超出 [LineChartGeometry.MAX_POINTS] 时丢弃最旧的。
 * 可选阈值线：超过阈值的整条曲线高亮为警示色（[showThreshold] = true）。
 *
 * 纯几何计算下沉到 [LineChartGeometry]（框架无关，可被 JUnit 覆盖）。
 */
class MetalChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val points = ArrayList<Float>(LineChartGeometry.MAX_POINTS)
    private var maxValue = 100f
    private var minValue = 0f
    private var threshold = 60f

    /** 是否绘制阈值线 + 超阈值变色（分贝仪等不需要阈值时设为 false） */
    private var showThreshold = true

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private fun Float.dp() = this * density
    private fun Float.sp() = this * scaledDensity

    // ---- Modern high-contrast light palette ----
    private val surfaceColor = 0xFFFFFFFF.toInt()
    private val gridColor = 0x14000000          // ~8% black, subtle
    private val alertColor = 0xFFE53935.toInt() // over-threshold / threshold line
    private val alertChipColor = 0x22E53935     // light red label chip

    private var mainColor = 0xFF0288D1.toInt()
    private var fillColor = Argb.withAlpha(mainColor, 0x24) // ~14% area fill

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = mainColor
        style = Paint.Style.STROKE
        strokeWidth = 2.5f.dp()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = alertColor
        style = Paint.Style.STROKE
        strokeWidth = 1.2f.dp()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f.dp(), 6f.dp()), 0f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp()
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = alertChipColor
        style = Paint.Style.FILL
    }
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = alertColor
        textSize = 11f.sp()
        isFakeBoldText = true
    }
    private val chipRect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "实时数据曲线图"
    }

    /** 配置外观：主色 / 是否显示阈值线 / 初始纵轴上下限 */
    fun configure(
        color: Int,
        showThreshold: Boolean,
        initMax: Float = 100f,
        initMin: Float = 0f
    ) {
        mainColor = color
        fillColor = Argb.withAlpha(color, 0x24)
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
        while (points.size > LineChartGeometry.MAX_POINTS) points.removeAt(0)
        // 自动放大纵轴范围
        maxValue = LineChartGeometry.zoomMax(maxValue, value)
        minValue = LineChartGeometry.zoomMin(minValue, value)
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

        // Light card surface (covers any translucent XML background).
        canvas.drawColor(surfaceColor)

        val geo = LineChartGeometry(w, h, points.size, minValue, maxValue)

        // 1. 网格：横向 3 条
        for (i in 1 until 4) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // 2. 阈值线 + 圆角标签
        if (geo.range > 0f && showThreshold && threshold in minValue..maxValue) {
            val ty = geo.thresholdY(threshold)
            canvas.drawLine(0f, ty, w, ty, thresholdPaint)
            drawThresholdChip(canvas, ty, "%.0f".format(threshold))
        }

        // 3. 数据曲线
        if (points.size < 2 || geo.range <= 0f) return

        val linePath = Path()
        val fillPath = Path()
        var first = true
        fillPath.moveTo(0f, h)
        for (i in points.indices) {
            val x = geo.xAt(i)
            val y = geo.yAt(points[i])
            if (first) {
                linePath.moveTo(x, y)
                fillPath.lineTo(x, y)
                first = false
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        val lastX = geo.xAt(points.size - 1)
        fillPath.lineTo(lastX, h)
        fillPath.lineTo(0f, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        // 超阈值时整条曲线变为警示色（仅 showThreshold 模式）
        val overThreshold = showThreshold &&
                (points.maxOrNull() ?: Float.NEGATIVE_INFINITY) > threshold
        linePaint.color = if (overThreshold) alertColor else mainColor
        canvas.drawPath(linePath, linePaint)
    }

    private fun drawThresholdChip(canvas: Canvas, ty: Float, label: String) {
        val pad = 6f.dp()
        val textW = chipTextPaint.measureText(label)
        val chipH = chipTextPaint.textSize + 4f.dp()
        val left = 8f.dp()
        val top = ty - chipH - 3f.dp()
        chipRect.set(left, top, left + textW + pad * 2, top + chipH)
        canvas.drawRoundRect(chipRect, 6f.dp(), 6f.dp(), chipPaint)
        val baseline = top + (chipH + chipTextPaint.textSize) / 2f - chipTextPaint.descent()
        canvas.drawText(label, left + pad, baseline, chipTextPaint)
    }
}
