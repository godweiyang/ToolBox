package com.example.videodownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * WiFi 信号热力图自定义 View。
 *
 * 数据模型：一条用户走动的轨迹，每个采样点包含 (x, y, rssi)。
 * - x, y 是世界坐标系下的位置（单位：米），由 PDR 推算
 * - rssi 用于着色：-50 dBm 以上绿色（信号强），-90 dBm 以下红色（信号弱）
 *
 * 绘制策略：
 * 1. 自动 fit 所有点到视图（保持长宽比，加边距）
 * 2. 每个采样点画一个半径 ~0.3m 的半透明色块（信号热力）
 * 3. 在采样点之间画连线，表示行走路径
 * 4. 起点用绿色圆圈标记，终点用红色圆圈标记
 *
 * 性能：单次重绘 < 5ms，不开启硬件层（数据频繁变化）。
 */
class HeatMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 单个采样点：世界坐标 + 信号强度 */
    data class Sample(
        val x: Float,   // 米
        val y: Float,   // 米
        val rssi: Int   // dBm
    )

    private val samples = mutableListOf<Sample>()

    // 视图坐标系变换参数
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = 0x66000000
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x22000000
    }

    /** 采样点半径（屏幕像素） */
    private val sampleRadiusPx = 40f

    /** 添加一个采样点并重绘 */
    fun addSample(x: Float, y: Float, rssi: Int) {
        samples.add(Sample(x, y, rssi))
        computeTransform()
        invalidate()
    }

    /** 清空轨迹 */
    fun clear() {
        samples.clear()
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        invalidate()
    }

    fun isEmpty() = samples.isEmpty()

    fun getSampleCount() = samples.size

    private fun computeTransform() {
        if (samples.isEmpty() || width == 0 || height == 0) return

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (s in samples) {
            if (s.x < minX) minX = s.x
            if (s.y < minY) minY = s.y
            if (s.x > maxX) maxX = s.x
            if (s.y > maxY) maxY = s.y
        }

        // 至少给 1 米范围，避免单点时除零
        val worldW = max(maxX - minX, 1f)
        val worldH = max(maxY - minY, 1f)

        val padding = sampleRadiusPx * 2 + 40f
        val availW = width - padding * 2
        val availH = height - padding * 2
        if (availW <= 0 || availH <= 0) return

        // 保持长宽比，按较小的缩放
        scale = min(availW / worldW, availH / worldH)

        // 把世界中心映射到视图中心
        val worldCenterX = (minX + maxX) / 2f
        val worldCenterY = (minY + maxY) / 2f
        offsetX = width / 2f - worldCenterX * scale
        offsetY = height / 2f - worldCenterY * scale
    }

    private fun worldToScreenX(x: Float) = offsetX + x * scale
    private fun worldToScreenY(y: Float) = offsetY + y * scale

    /** 根据 RSSI 返回颜色：-50 以上绿，-90 以下红，中间渐变 */
    private fun rssiToColor(rssi: Int): Int {
        // 归一化到 0..1，0=最弱(-90)，1=最强(-30)
        val t = ((rssi + 90) / 60f).coerceIn(0f, 1f)
        // 绿(0x2E7D32) → 黄(0xF9A825) → 红(0xB71C1C)
        return when {
            t < 0.5f -> {
                // 红 → 黄
                val k = t * 2f
                lerpColor(0xB71C1C, 0xF9A825, k)
            }
            else -> {
                // 黄 → 绿
                val k = (t - 0.5f) * 2f
                lerpColor(0xF9A825, 0x2E7D32, k)
            }
        }
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val r1 = c1 shr 16 and 0xFF
        val g1 = c1 shr 8 and 0xFF
        val b1 = c1 and 0xFF
        val r2 = c2 shr 16 and 0xFF
        val g2 = c2 shr 8 and 0xFF
        val b2 = c2 and 0xFF
        val r = (r1 + (r2 - r1) * t).toInt()
        val g = (g1 + (g2 - g1) * t).toInt()
        val b = (b1 + (b2 - b1) * t).toInt()
        return Color.argb(180, r, g, b)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeTransform()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // 画 1 米间隔的网格作为尺度参考
        drawGrid(canvas)

        if (samples.isEmpty()) return

        // 1. 先画半透明色块（热力图）
        for (s in samples) {
            val cx = worldToScreenX(s.x)
            val cy = worldToScreenY(s.y)
            pointPaint.color = rssiToColor(s.rssi)
            canvas.drawCircle(cx, cy, sampleRadiusPx, pointPaint)
        }

        // 2. 画路径连线
        if (samples.size >= 2) {
            val path = Path()
            path.moveTo(worldToScreenX(samples[0].x), worldToScreenY(samples[0].y))
            for (i in 1 until samples.size) {
                path.lineTo(worldToScreenX(samples[i].x), worldToScreenY(samples[i].y))
            }
            canvas.drawPath(path, pathPaint)
        }

        // 3. 起点和终点标记
        val start = samples.first()
        val end = samples.last()

        markerPaint.color = 0xFF2196F3.toInt()
        canvas.drawCircle(worldToScreenX(start.x), worldToScreenY(start.y), 14f, markerPaint)
        canvas.drawText("S", worldToScreenX(start.x), worldToScreenY(start.y) + 10f, textPaint)

        if (samples.size >= 2) {
            markerPaint.color = 0xFFD32F2F.toInt()
            canvas.drawCircle(worldToScreenX(end.x), worldToScreenY(end.y), 14f, markerPaint)
            canvas.drawText("E", worldToScreenX(end.x), worldToScreenY(end.y) + 10f, textPaint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        if (scale <= 0f) return
        // 每 1 米一条网格线
        val stepWorld = 1f
        val stepPx = stepWorld * scale
        if (stepPx < 20f) return  // 太密就不画

        var x = 0f
        while (worldToScreenX(x) < width) {
            val sx = worldToScreenX(x)
            canvas.drawLine(sx, 0f, sx, height.toFloat(), gridPaint)
            x += stepWorld
        }
        var y = 0f
        while (worldToScreenY(y) < height) {
            val sy = worldToScreenY(y)
            canvas.drawLine(0f, sy, width.toFloat(), sy, gridPaint)
            y += stepWorld
        }
    }
}
