package com.example.videodownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * WiFi 信号热力图（点状云风格）。
 *
 * 数据模型：用户在不同位置手动标记的采样点 (x, y, rssi)。
 * - x, y 是视图坐标系下的像素位置（用户点击位置）
 * - rssi 用于着色：-50 dBm 以上绿色（信号强），-90 dBm 以下红色（信号弱）
 *
 * 渲染策略：
 * 1. 每个采样点画一个大的径向渐变色块（从中心颜色渐变到透明）
 * 2. 多个色块自然叠加形成"信号云"效果，类似天气预报热力图
 * 3. 中心画小圆点+信号等级文字，标注采样位置
 *
 * 不再依赖 PDR 步行推算，避免原地晃动误判。
 */
class HeatMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 单个采样点：视图坐标 + 信号强度 */
    data class Sample(
        val x: Float,   // 视图像素 x
        val y: Float,   // 视图像素 y
        val rssi: Int   // dBm
    )

    private val samples = mutableListOf<Sample>()

    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // 用 SRC_OVER 叠加，半透明色块自然融合
        alpha = 200
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF333333.toInt()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC333333.toInt()
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x15000000
    }

    /** 云块半径（像素） */
    private val cloudRadius = 120f

    /** 添加一个采样点并重绘 */
    fun addSample(x: Float, y: Float, rssi: Int) {
        samples.add(Sample(x, y, rssi))
        invalidate()
    }

    /** 清空 */
    fun clear() {
        samples.clear()
        invalidate()
    }

    fun isEmpty() = samples.isEmpty()

    fun getSampleCount() = samples.size

    /** 根据 RSSI 返回颜色：-50 以上绿，-90 以下红，中间渐变 */
    private fun rssiToColor(rssi: Int): Int {
        // 归一化到 0..1，0=最弱(-90)，1=最强(-30)
        val t = ((rssi + 90) / 60f).coerceIn(0f, 1f)
        // 红(0xE53935) → 黄(0xFDD835) → 绿(0x43A047)
        return when {
            t < 0.5f -> {
                val k = t * 2f
                lerpColor(0xE53935, 0xFDD835, k)
            }
            else -> {
                val k = (t - 0.5f) * 2f
                lerpColor(0xFDD835, 0x43A047, k)
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
        return Color.argb(255, r, g, b)
    }

    /** 根据 RSSI 返回等级文字 */
    private fun rssiToLevel(rssi: Int): String = when {
        rssi >= -55 -> "极佳"
        rssi >= -65 -> "良好"
        rssi >= -75 -> "一般"
        rssi >= -85 -> "较弱"
        else -> "很差"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // 画浅色网格作为背景参考
        drawGrid(canvas)

        if (samples.isEmpty()) return

        // 1. 画点状云：每个采样点一个径向渐变色块
        for (s in samples) {
            val color = rssiToColor(s.rssi)
            // 径向渐变：中心实色 → 边缘透明
            val shader = RadialGradient(
                s.x, s.y, cloudRadius,
                intArrayOf(color, color and 0x00FFFFFF),
                floatArrayOf(0.3f, 1f),
                Shader.TileMode.CLAMP
            )
            cloudPaint.shader = shader
            cloudPaint.alpha = 180
            canvas.drawCircle(s.x, s.y, cloudRadius, cloudPaint)
        }
        cloudPaint.shader = null

        // 2. 画每个采样点的标记（小圆点 + 等级标签）
        for (s in samples) {
            // 中心小圆点
            dotPaint.color = rssiToColor(s.rssi)
            canvas.drawCircle(s.x, s.y, 10f, dotPaint)
            // 白色描边
            dotPaint.style = Paint.Style.STROKE
            dotPaint.color = Color.WHITE
            dotPaint.strokeWidth = 2f
            canvas.drawCircle(s.x, s.y, 10f, dotPaint)
            dotPaint.style = Paint.Style.FILL

            // 等级文字标签（带背景）
            val label = "${rssiToLevel(s.rssi)} ${s.rssi}"
            val textWidth = textPaint.measureText(label)
            val textHeight = textPaint.textSize
            val bgLeft = s.x - textWidth / 2 - 8f
            val bgTop = s.y - cloudRadius * 0.3f - textHeight / 2 - 4f
            val bgRight = s.x + textWidth / 2 + 8f
            val bgBottom = s.y - cloudRadius * 0.3f + textHeight / 2 + 4f
            // 圆角背景
            textBgPaint.color = rssiToColor(s.rssi) or 0xFF000000.toInt()
            canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, 8f, 8f, textBgPaint)
            canvas.drawText(label, s.x, s.y - cloudRadius * 0.3f + textHeight / 3f, textPaint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val step = 60f  // 每 60px 一条
        var x = step
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = step
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }
    }
}
