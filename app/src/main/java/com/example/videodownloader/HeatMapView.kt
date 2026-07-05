package com.example.videodownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewParent
import kotlin.math.max
import kotlin.math.min

/**
 * WiFi 信号热力图（点状云风格，支持双指缩放和拖动）。
 *
 * 数据模型：用户走动时自动采集的采样点 (x, y, rssi)。
 * - x, y 是"世界坐标"（自动采集时以起点为 0，每次采样点位置基于上次位置 + 随机抖动）
 * - rssi 用于着色：-50 dBm 以上绿色（信号强），-90 dBm 以下红色（信号弱）
 *
 * 渲染策略：
 * 1. 自动 fit 所有点到视图（保持长宽比），用户可通过双指缩放放大查看
 * 2. 每个采样点画径向渐变色块，多个色块叠加形成"信号云"
 * 3. 中心画小圆点+信号等级文字
 *
 * 手势：
 * - 双指捏合缩放（0.3x ~ 8x）
 * - 单指拖动平移（缩放后查看不同区域）
 */
class HeatMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 单个采样点：世界坐标 + 信号强度 */
    data class Sample(
        val x: Float,   // 世界坐标 x
        val y: Float,   // 世界坐标 y
        val rssi: Int   // dBm
    )

    private val samples = mutableListOf<Sample>()

    // ===== 视图变换 =====
    /** 自动 fit 的基础缩放（让所有点正好填满视图） */
    private var baseScale = 1f
    /** 用户捏合的额外缩放倍数 */
    private var userScale = 1f
    /** 总缩放 = baseScale * userScale */
    private val totalScale: Float get() = baseScale * userScale
    /** 世界坐标 -> 视图坐标的偏移 */
    private var offsetX = 0f
    private var offsetY = 0f
    /** 自动 fit 计算出的中心偏移（用于重置） */
    private var autoOffsetX = 0f
    private var autoOffsetY = 0f

    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x15000000
    }

    /** 云块半径（世界坐标单位，约对应 0.5m） */
    private val cloudRadiusWorld = 0.5f

    // ===== 手势检测 =====
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            userScale = (userScale * factor).coerceIn(0.3f, 8f)
            // 以捏合中心为缩放支点
            val focusWorldX = screenToWorldX(detector.focusX)
            val focusWorldY = screenToWorldY(detector.focusY)
            computeTransform()
            // 调整 offset 使 focus 点保持在原屏幕位置
            val newFocusScreenX = worldToScreenX(focusWorldX)
            val newFocusScreenY = worldToScreenY(focusWorldY)
            offsetX += detector.focusX - newFocusScreenX
            offsetY += detector.focusY - newFocusScreenY
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            // 单指拖动平移（仅当用户已放大时才有意义）
            offsetX -= distanceX
            offsetY -= distanceY
            invalidate()
            return true
        }
    })

    init {
        // 让父 View 不要拦截触摸事件，确保手势能传到本 View
        // （在 Fragment 里 setOnTouchListener 会拦截，这里改用 onTouchEvent）
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // 双指缩放中，或已放大后的单指拖动，都要阻止父级（ViewPager2）拦截触摸事件
        // 否则左右滑动会被 ViewPager2 当作切 Tab
        val pointerCount = event.pointerCount
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount >= 2 || userScale > 1.01f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointerCount >= 2 || userScale > 1.01f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    /** 添加一个采样点并重绘 */
    fun addSample(x: Float, y: Float, rssi: Int) {
        samples.add(Sample(x, y, rssi))
        computeTransform()
        invalidate()
    }

    /** 清空 */
    fun clear() {
        samples.clear()
        userScale = 1f
        computeTransform()
        invalidate()
    }

    /** 重置视图（恢复到自动 fit 状态） */
    fun resetView() {
        userScale = 1f
        computeTransform()
        invalidate()
    }

    fun isEmpty() = samples.isEmpty()

    fun getSampleCount() = samples.size

    /** 计算自动 fit 的缩放和偏移（基于所有采样点） */
    private fun computeTransform() {
        if (samples.isEmpty() || width == 0 || height == 0) {
            baseScale = 1f
            offsetX = width / 2f
            offsetY = height / 2f
            autoOffsetX = offsetX
            autoOffsetY = offsetY
            return
        }

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

        // 至少给 1 单位范围，避免单点时除零
        val worldW = max(maxX - minX, 1f)
        val worldH = max(maxY - minY, 1f)

        val padding = 80f
        val availW = width - padding * 2
        val availH = height - padding * 2
        if (availW <= 0 || availH <= 0) return

        // 保持长宽比，按较小的缩放
        baseScale = min(availW / worldW, availH / worldH)

        // 把世界中心映射到视图中心
        val worldCenterX = (minX + maxX) / 2f
        val worldCenterY = (minY + maxY) / 2f
        autoOffsetX = width / 2f - worldCenterX * totalScale
        autoOffsetY = height / 2f - worldCenterY * totalScale
        // 用户未拖动时，offset 跟随 autoOffset
        // 仅在 userScale == 1f 时重置 offset（避免覆盖用户的拖动）
        if (userScale == 1f) {
            offsetX = autoOffsetX
            offsetY = autoOffsetY
        }
    }

    private fun worldToScreenX(x: Float) = offsetX + x * totalScale
    private fun worldToScreenY(y: Float) = offsetY + y * totalScale
    private fun screenToWorldX(x: Float) = (x - offsetX) / totalScale
    private fun screenToWorldY(y: Float) = (y - offsetY) / totalScale

    /** 根据 RSSI 返回颜色 */
    private fun rssiToColor(rssi: Int): Int {
        val t = ((rssi + 90) / 60f).coerceIn(0f, 1f)
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

    private fun rssiToLevel(rssi: Int): String = when {
        rssi >= -55 -> "极佳"
        rssi >= -65 -> "良好"
        rssi >= -75 -> "一般"
        rssi >= -85 -> "较弱"
        else -> "很差"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeTransform()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        drawGrid(canvas)

        if (samples.isEmpty()) return

        val cloudRadiusScreen = cloudRadiusWorld * totalScale

        // 1. 画点状云
        for (s in samples) {
            val cx = worldToScreenX(s.x)
            val cy = worldToScreenY(s.y)
            // 跳过视图外的点（性能优化）
            if (cx < -cloudRadiusScreen || cx > width + cloudRadiusScreen ||
                cy < -cloudRadiusScreen || cy > height + cloudRadiusScreen
            ) continue

            val color = rssiToColor(s.rssi)
            val radius = cloudRadiusScreen.coerceAtLeast(20f)  // 缩太小也至少 20px
            val shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(color, color and 0x00FFFFFF),
                floatArrayOf(0.3f, 1f),
                Shader.TileMode.CLAMP
            )
            cloudPaint.shader = shader
            cloudPaint.alpha = 180
            canvas.drawCircle(cx, cy, radius, cloudPaint)
        }
        cloudPaint.shader = null
        // 不再画中心标记和文字标签，只保留色块
    }

    private fun drawGrid(canvas: Canvas) {
        // 在世界坐标系下每 1 单位画一条网格线
        if (totalScale <= 0f) return
        val stepWorld = 1f
        val stepPx = stepWorld * totalScale
        if (stepPx < 20f) return  // 太密就不画

        // 找到视图范围对应的世界坐标范围
        val worldLeft = screenToWorldX(0f)
        val worldRight = screenToWorldX(width.toFloat())
        val worldTop = screenToWorldY(0f)
        val worldBottom = screenToWorldY(height.toFloat())

        val startX = Math.floor(worldLeft.toDouble()).toInt()
        val endX = Math.ceil(worldRight.toDouble()).toInt()
        val startY = Math.floor(worldTop.toDouble()).toInt()
        val endY = Math.ceil(worldBottom.toDouble()).toInt()

        var x = startX.toFloat()
        while (x <= endX) {
            val sx = worldToScreenX(x)
            canvas.drawLine(sx, 0f, sx, height.toFloat(), gridPaint)
            x += stepWorld
        }
        var y = startY.toFloat()
        while (y <= endY) {
            val sy = worldToScreenY(y)
            canvas.drawLine(0f, sy, width.toFloat(), sy, gridPaint)
            y += stepWorld
        }
    }
}
