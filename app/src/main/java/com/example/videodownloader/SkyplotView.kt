package com.example.videodownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.videodownloader.chart.SkyGeometry

/**
 * 卫星天空图（Skyplot）：以观察者头顶为中心的俯视图。
 *
 * 圆心 = 天顶（仰角 90°），外圈 = 地平线（仰角 0°）。
 * 方位角 0°=北（上）、90°=东（右）、180°=南（下）、270°=西（左）。
 *
 * 每颗卫星按 (azimuth, elevation) 定位，颜色区分星座，大小反映信号强度（C/N0）。
 * 点击某颗卫星可选中并高亮，回调 [onSatelliteSelected]。
 *
 * 极坐标/命中几何下沉到 [SkyGeometry]（框架无关，可被 JUnit 覆盖）。
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

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private fun Float.dp() = this * density
    private fun Float.sp() = this * scaledDensity

    // ---- Modern light palette ----
    private val surfaceColor = 0xFFFFFFFF.toInt()
    private val ringOuterColor = 0xFF9AA0A6.toInt()
    private val ringInnerColor = 0xFFD8DAE0.toInt()
    private val spokeColor = 0x1F000000
    private val compassColor = 0xFF5F6368.toInt()
    private val elevLabelColor = 0xFF9AA0A6.toInt()
    private val satLabelColor = 0xFF3C4043.toInt()
    private val selectionColor = 0xFF007AFF.toInt()

    private val ringOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f.dp()
        color = ringOuterColor
    }
    private val ringInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp()
        color = ringInnerColor
    }
    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp()
        color = spokeColor
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f.dp(), 6f.dp()), 0f)
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ringInnerColor
    }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f.sp()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = compassColor
        textAlign = Paint.Align.CENTER
    }
    private val elevationLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f.sp()
        color = elevLabelColor
        textAlign = Paint.Align.CENTER
    }
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val satStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f.dp()
        color = 0xFFFFFFFF.toInt()
    }
    private val satLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f.sp()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = satLabelColor
        textAlign = Paint.Align.CENTER
    }
    private val selectedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f.dp()
        color = selectionColor
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "卫星天空图，双击或点击卫星查看详情"
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

    private fun buildGeometry(): SkyGeometry? {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(cx, cy) - 16f.dp()
        if (maxR <= 0f) return null
        return SkyGeometry(cx, cy, maxR)
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
        val g = buildGeometry() ?: return
        val points = satellites.map { g.position(it.azimuth, it.elevation) }
        val best = g.nearest(points, touchX, touchY, 48f.dp())
        selectedIndex = best
        onSatelliteSelected?.invoke(if (best >= 0) satellites[best] else null)
        if (best >= 0) {
            val s = satellites[best]
            announceForAccessibility(
                "已选择 ${constellationName(s.constellation)} 卫星 ${s.svid}，" +
                        "仰角 ${s.elevation.toInt()} 度，方位 ${s.azimuth.toInt()} 度"
            )
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(surfaceColor)
        val g = buildGeometry() ?: return
        val cx = g.cx
        val cy = g.cy
        val maxR = g.maxR
        val rings = g.ringRadii()

        // 1. 仰角同心圆：0°(外圈) / 30° / 60° / 90°(圆心)
        canvas.drawCircle(cx, cy, rings[0], ringOuterPaint)
        canvas.drawCircle(cx, cy, rings[1], ringInnerPaint)
        canvas.drawCircle(cx, cy, rings[2], ringInnerPaint)
        canvas.drawCircle(cx, cy, 3f.dp(), centerPaint)

        // 2. 方位辐条
        canvas.drawLine(cx, cy - maxR, cx, cy + maxR, spokePaint)
        canvas.drawLine(cx - maxR, cy, cx + maxR, cy, spokePaint)

        // 3. 方位字母
        canvas.drawText("N", cx, cy - maxR - 6f.dp(), compassPaint)
        canvas.drawText("S", cx, cy + maxR + 22f.sp(), compassPaint)
        canvas.drawText("E", cx + maxR + 14f.dp(), cy + 4f.sp(), compassPaint)
        canvas.drawText("W", cx - maxR - 14f.dp(), cy + 4f.sp(), compassPaint)

        // 4. 仰角刻度
        canvas.drawText("60°", cx + 6f.dp(), cy - rings[2] + 4f.sp(), elevationLabelPaint)
        canvas.drawText("30°", cx + 6f.dp(), cy - rings[1] + 4f.sp(), elevationLabelPaint)

        // 5. 卫星点
        for (i in satellites.indices) {
            val s = satellites[i]
            val p = g.position(s.azimuth, s.elevation)
            val radius = g.satRadius(s.cn0)

            satPaint.color = constellationColorValue(s.constellation)
            satPaint.alpha = if (s.usedInFix) 255 else 90

            canvas.drawCircle(p.sx, p.sy, radius, satPaint)
            canvas.drawCircle(p.sx, p.sy, radius, satStrokePaint)

            if (s.cn0 >= 35f && s.usedInFix) {
                canvas.drawText(
                    constellationPrefix(s.constellation) + s.svid,
                    p.sx, p.sy - radius - 4f.dp(), satLabelPaint
                )
            }

            if (i == selectedIndex) {
                canvas.drawCircle(p.sx, p.sy, radius + 6f.dp(), selectedRingPaint)
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

        // 星座色保留原值（卫星列表 UI 也依赖，改动会与列表不一致）。
        fun constellationColorValue(c: Int): Int = when (c) {
            CONSTELLATION_GPS -> 0xFF1E88E5.toInt()
            CONSTELLATION_GLONASS -> 0xFFE53935.toInt()
            CONSTELLATION_GALILEO -> 0xFF43A047.toInt()
            CONSTELLATION_BEIDOU -> 0xFFFB8C00.toInt()
            else -> 0xFF8E24AA.toInt()
        }
    }
}
