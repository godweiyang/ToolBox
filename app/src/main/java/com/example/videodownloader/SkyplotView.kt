package com.example.videodownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 卫星天空图（Skyplot）：以观察者头顶为中心的俯视图。
 *
 * 圆心 = 天顶（仰角 90°，正头顶），外圈 = 地平线（仰角 0°）。
 * 方位角 0°=北（上）、90°=东（右）、180°=南（下）、270°=西（左）。
 *
 * 每颗卫星按 (azimuth, elevation) 定位，颜色区分星座，大小反映信号强度（C/N0）。
 * 点击某颗卫星可选中并高亮，回调 [onSatelliteSelected]。
 */
class SkyplotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 单颗卫星的显示数据 */
    data class Sat(
        val svid: Int,
        val constellation: Int,
        val azimuth: Float,
        val elevation: Float,
        val cn0: Float,
        val usedInFix: Boolean
    )

    private var satellites: List<Sat> = emptyList()
    private var selectedIndex = -1

    var onSatelliteSelected: ((Sat?) -> Unit)? = null

    private var touchX = -1f
    private var touchY = -1f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xFFBDBDBD.toInt()
    }
    private val ringPaintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF9E9E9E.toInt()
    }
    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x33000000
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 6f), 0f)
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF9E9E9E.toInt()
    }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFF616161.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val elevationLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        color = 0xFFBDBDBD.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val satStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFFFFFFF.toInt()
    }
    private val satLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFF424242.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val selectedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF6750A4.toInt()
    }

    /** 更新卫星列表并重绘 */
    fun updateSatellites(sats: List<Sat>) {
        satellites = sats
        if (selectedIndex >= satellites.size) selectedIndex = -1
        invalidate()
    }

    /** 清除选中状态 */
    fun clearSelection() {
        selectedIndex = -1
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
            touchX = event.x
            touchY = event.y
            findNearestSatellite()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun findNearestSatellite() {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(cx, cy) - 16f
        if (maxR <= 0f) return

        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        for (i in satellites.indices) {
            val s = satellites[i]
            val r = (1f - s.elevation / 90f) * maxR
            val angle = Math.toRadians(s.azimuth.toDouble())
            val sx = cx + (r * sin(angle)).toFloat()
            val sy = cy - (r * cos(angle)).toFloat()
            val d = hypot(touchX - sx, touchY - sy)
            if (d < bestDist && d < 48f) {
                bestDist = d
                bestIdx = i
            }
        }
        selectedIndex = bestIdx
        onSatelliteSelected?.invoke(if (selectedIndex >= 0) satellites[selectedIndex] else null)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(cx, cy) - 16f
        if (maxR <= 0f) return

        // 1. 仰角同心圆：0°(外圈) / 30° / 60° / 90°(圆心)
        canvas.drawCircle(cx, cy, maxR, ringPaintOuter)
        canvas.drawCircle(cx, cy, maxR * 2f / 3f, ringPaint)
        canvas.drawCircle(cx, cy, maxR / 3f, ringPaint)
        canvas.drawCircle(cx, cy, 3f, centerPaint)

        // 2. 方位辐条
        canvas.drawLine(cx, cy - maxR, cx, cy + maxR, spokePaint)
        canvas.drawLine(cx - maxR, cy, cx + maxR, cy, spokePaint)

        // 3. 方位字母
        canvas.drawText("N", cx, cy - maxR - 6f, compassPaint)
        canvas.drawText("S", cx, cy + maxR + 30f, compassPaint)
        canvas.drawText("E", cx + maxR + 18f, cy + 10f, compassPaint)
        canvas.drawText("W", cx - maxR - 18f, cy + 10f, compassPaint)

        // 4. 仰角刻度
        canvas.drawText("60°", cx + 8f, cy - maxR / 3f + 6f, elevationLabelPaint)
        canvas.drawText("30°", cx + 8f, cy - maxR * 2f / 3f + 6f, elevationLabelPaint)

        // 5. 卫星点
        for (i in satellites.indices) {
            val s = satellites[i]
            val r = (1f - s.elevation / 90f) * maxR
            val angle = Math.toRadians(s.azimuth.toDouble())
            val sx = cx + (r * sin(angle)).toFloat()
            val sy = cy - (r * cos(angle)).toFloat()

            val color = constellationColorValue(s.constellation)
            val cn0Clamped = s.cn0.coerceIn(15f, 50f)
            val radius = 5f + (cn0Clamped - 15f) / 35f * 7f

            satPaint.color = color
            satPaint.alpha = if (s.usedInFix) 255 else 90

            canvas.drawCircle(sx, sy, radius, satPaint)
            canvas.drawCircle(sx, sy, radius, satStrokePaint)

            if (s.cn0 >= 35f && s.usedInFix) {
                canvas.drawText(
                    constellationPrefix(s.constellation) + s.svid,
                    sx, sy - radius - 4f, satLabelPaint
                )
            }

            if (i == selectedIndex) {
                canvas.drawCircle(sx, sy, radius + 6f, selectedRingPaint)
            }
        }
    }

    private fun constellationPrefix(c: Int) = when (c) {
        CONSTELLATION_GPS -> "G"
        CONSTELLATION_GLONASS -> "R"
        CONSTELLATION_GALILEO -> "E"
        CONSTELLATION_BEIDOU -> "C"
        CONSTELLATION_SBAS -> "S"
        CONSTELLATION_QZSS -> "J"
        else -> "?"
    }

    companion object {
        const val CONSTELLATION_UNKNOWN = 0
        const val CONSTELLATION_GPS = 1
        const val CONSTELLATION_SBAS = 2
        const val CONSTELLATION_GLONASS = 3
        const val CONSTELLATION_QZSS = 4
        const val CONSTELLATION_BEIDOU = 5
        const val CONSTELLATION_GALILEO = 6

        fun constellationName(c: Int): String = when (c) {
            CONSTELLATION_GPS -> "GPS"
            CONSTELLATION_GLONASS -> "GLONASS"
            CONSTELLATION_GALILEO -> "Galileo"
            CONSTELLATION_BEIDOU -> "北斗"
            CONSTELLATION_SBAS -> "SBAS"
            CONSTELLATION_QZSS -> "QZSS"
            else -> "未知"
        }

        fun constellationColorValue(c: Int): Int = when (c) {
            CONSTELLATION_GPS -> 0xFF1E88E5.toInt()
            CONSTELLATION_GLONASS -> 0xFFE53935.toInt()
            CONSTELLATION_GALILEO -> 0xFF43A047.toInt()
            CONSTELLATION_BEIDOU -> 0xFFFB8C00.toInt()
            else -> 0xFF8E24AA.toInt()
        }
    }
}
