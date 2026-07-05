package com.example.videodownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 裁剪框叠加 View：覆盖在视频预览上，用户可拖拽 4 个边和 4 个角来调整裁剪区域。
 *
 * 坐标系：view 自身坐标，[0, width] x [0, height]
 *
 * 用法：
 *   - 放在 FrameLayout 里，跟 VideoView/ImageView 叠加
 *   - setVideoAspectRatio(w, h) 设置视频宽高比，叠加层会按比例计算有效区域
 *   - getCropRect() 返回归一化的裁剪区域 [0,1] x [0,1]（相对视频帧）
 *
 * 视觉：
 *   - 暗色遮罩盖住非裁剪区
 *   - 白色描边 + 8 个手柄（4 边中点 + 4 角）
 *   - 三分线辅助构图
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 当前裁剪框（view 坐标） */
    private val cropRect = RectF()
    /** 视频显示区域（view 坐标，按宽高比 letterbox 后的有效区） */
    private val videoRect = RectF()

    private var videoAspect = 1f  // w/h
    private var videoW = 0        // 视频实际像素宽
    private var videoH = 0        // 视频实际像素高
    private var initialized = false

    // 拖拽状态
    private var dragMode = DRAG_NONE
    private var lastX = 0f
    private var lastY = 0f

    // 手柄大小
    private val handleSizePx = 48f  // 触控热区半径
    private val handleDrawSize = 24f  // 绘制半径

    // 画笔
    private val maskPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }
    // 尺寸标签画笔
    private val labelBgPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        isFakeBoldText = true
    }

    /** 设置视频实际像素宽高,用于显示裁剪尺寸 */
    fun setVideoAspectRatio(w: Int, h: Int) {
        videoW = w
        videoH = h
        videoAspect = if (h > 0) w.toFloat() / h else 1f
        computeVideoRect()
        invalidate()
    }

    /** 获取归一化裁剪区域 [0,1] x [0,1]，相对视频帧 */
    fun getCropRect(): RectF {
        val r = RectF()
        if (videoRect.width() <= 0 || videoRect.height() <= 0) return r
        r.left = (cropRect.left - videoRect.left) / videoRect.width()
        r.top = (cropRect.top - videoRect.top) / videoRect.height()
        r.right = (cropRect.right - videoRect.left) / videoRect.width()
        r.bottom = (cropRect.bottom - videoRect.top) / videoRect.height()
        return r
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeVideoRect()
        if (!initialized && videoRect.width() > 0) {
            // 初始裁剪框 = 视频显示区（不裁剪）
            cropRect.set(videoRect)
            // 缩到 80% 让用户能扩展
            cropRect.inset(videoRect.width() * 0.1f, videoRect.height() * 0.1f)
            initialized = true
            invalidate()
        }
    }

    /** 按视频宽高比计算视频在 view 里的显示区（居中 letterbox） */
    private fun computeVideoRect() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val viewAspect = vw / vh
        if (viewAspect > videoAspect) {
            // view 更宽，视频按高度填满，左右留白
            val realW = vh * videoAspect
            val left = (vw - realW) / 2
            videoRect.set(left, 0f, left + realW, vh)
        } else {
            val realH = vw / videoAspect
            val top = (vh - realH) / 2
            videoRect.set(0f, top, vw, top + realH)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (videoRect.width() <= 0) return

        // 1. 四周暗色遮罩
        // 上
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, maskPaint)
        // 下
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), maskPaint)
        // 左
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, maskPaint)
        // 右
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, maskPaint)

        // 2. 裁剪框白边
        canvas.drawRect(cropRect, borderPaint)

        // 3. 三分线
        val x1 = cropRect.left + cropRect.width() / 3
        val x2 = cropRect.left + cropRect.width() * 2 / 3
        val y1 = cropRect.top + cropRect.height() / 3
        val y2 = cropRect.top + cropRect.height() * 2 / 3
        canvas.drawLine(x1, cropRect.top, x1, cropRect.bottom, gridPaint)
        canvas.drawLine(x2, cropRect.top, x2, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, y1, cropRect.right, y1, gridPaint)
        canvas.drawLine(cropRect.left, y2, cropRect.right, y2, gridPaint)

        // 4. 8 个手柄
        drawHandles(canvas)

        // 5. 尺寸标签:显示当前裁剪区域的视频像素尺寸(宽×高),拖动时实时更新
        drawSizeLabel(canvas)
    }

    /** 在裁剪框左上角画尺寸标签,显示视频像素尺寸(如 720×1280) */
    private fun drawSizeLabel(canvas: Canvas) {
        if (videoW <= 0 || videoH <= 0 || videoRect.width() <= 0) return
        // 归一化裁剪区域 × 视频实际像素 = 裁剪后的视频像素尺寸
        val normW = cropRect.width() / videoRect.width()
        val normH = cropRect.height() / videoRect.height()
        val cropPxW = (videoW * normW).toInt().coerceAtLeast(1)
        val cropPxH = (videoH * normH).toInt().coerceAtLeast(1)
        val text = "${cropPxW}×${cropPxH}"

        // 标签位置:裁剪框左上角外侧(若空间不够则内侧)
        val pad = 8f
        val textW = labelTextPaint.measureText(text)
        val textH = labelTextPaint.textSize
        val bgW = textW + pad * 2
        val bgH = textH + pad * 2
        // 优先放在裁剪框左上角外侧上方;若上方空间不足(贴边),放裁剪框内侧左上
        var bgLeft = cropRect.left
        var bgTop = cropRect.top - bgH - 4f
        if (bgTop < 0) bgTop = cropRect.top + 4f
        if (bgLeft + bgW > width) bgLeft = width - bgW
        if (bgLeft < 0) bgLeft = 0f

        canvas.drawRect(bgLeft, bgTop, bgLeft + bgW, bgTop + bgH, labelBgPaint)
        canvas.drawText(text, bgLeft + pad, bgTop + textH, labelTextPaint)
    }

    private fun drawHandles(canvas: Canvas) {
        val left = cropRect.left
        val top = cropRect.top
        val right = cropRect.right
        val bottom = cropRect.bottom
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        // 4 边中点
        canvas.drawCircle(cx, top, handleDrawSize / 2, handlePaint)
        canvas.drawCircle(cx, bottom, handleDrawSize / 2, handlePaint)
        canvas.drawCircle(left, cy, handleDrawSize / 2, handlePaint)
        canvas.drawCircle(right, cy, handleDrawSize / 2, handlePaint)
        // 4 角
        canvas.drawRect(left - handleDrawSize / 2, top - handleDrawSize / 2, left + handleDrawSize / 2, top + handleDrawSize / 2, handlePaint)
        canvas.drawRect(right - handleDrawSize / 2, top - handleDrawSize / 2, right + handleDrawSize / 2, top + handleDrawSize / 2, handlePaint)
        canvas.drawRect(left - handleDrawSize / 2, bottom - handleDrawSize / 2, left + handleDrawSize / 2, bottom + handleDrawSize / 2, handlePaint)
        canvas.drawRect(right - handleDrawSize / 2, bottom - handleDrawSize / 2, right + handleDrawSize / 2, bottom + handleDrawSize / 2, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragMode = hitTest(event.x, event.y)
                if (dragMode != DRAG_NONE) {
                    // 阻止父级(ScrollView)拦截后续触摸事件,避免拖框时整个页面跟着滚
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DRAG_NONE) return false
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                applyDrag(dx, dy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != DRAG_NONE) {
                    // 恢复,允许父级正常处理触摸
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                dragMode = DRAG_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 判断触点落在哪个手柄上 */
    private fun hitTest(x: Float, y: Float): Int {
        val left = cropRect.left
        val top = cropRect.top
        val right = cropRect.right
        val bottom = cropRect.bottom
        val hs = handleSizePx

        // 4 角优先
        if (dist(x, y, left, top) < hs) return DRAG_TOP_LEFT
        if (dist(x, y, right, top) < hs) return DRAG_TOP_RIGHT
        if (dist(x, y, left, bottom) < hs) return DRAG_BOTTOM_LEFT
        if (dist(x, y, right, bottom) < hs) return DRAG_BOTTOM_RIGHT
        // 4 边
        if (abs(x - left) < hs && y in top..bottom) return DRAG_LEFT
        if (abs(x - right) < hs && y in top..bottom) return DRAG_RIGHT
        if (abs(y - top) < hs && x in left..right) return DRAG_TOP
        if (abs(y - bottom) < hs && x in left..right) return DRAG_BOTTOM
        // 内部拖动整个框
        if (x in left..right && y in top..bottom) return DRAG_MOVE
        return DRAG_NONE
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun abs(v: Float) = if (v < 0) -v else v

    /** 根据拖拽模式调整裁剪框，约束在视频显示区内 */
    private fun applyDrag(dx: Float, dy: Float) {
        val minSize = 60f  // 最小裁剪框尺寸
        when (dragMode) {
            DRAG_TOP_LEFT -> {
                cropRect.left = (cropRect.left + dx).coerceIn(videoRect.left, cropRect.right - minSize)
                cropRect.top = (cropRect.top + dy).coerceIn(videoRect.top, cropRect.bottom - minSize)
            }
            DRAG_TOP_RIGHT -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, videoRect.right)
                cropRect.top = (cropRect.top + dy).coerceIn(videoRect.top, cropRect.bottom - minSize)
            }
            DRAG_BOTTOM_LEFT -> {
                cropRect.left = (cropRect.left + dx).coerceIn(videoRect.left, cropRect.right - minSize)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, videoRect.bottom)
            }
            DRAG_BOTTOM_RIGHT -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, videoRect.right)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, videoRect.bottom)
            }
            DRAG_LEFT -> {
                cropRect.left = (cropRect.left + dx).coerceIn(videoRect.left, cropRect.right - minSize)
            }
            DRAG_RIGHT -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, videoRect.right)
            }
            DRAG_TOP -> {
                cropRect.top = (cropRect.top + dy).coerceIn(videoRect.top, cropRect.bottom - minSize)
            }
            DRAG_BOTTOM -> {
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, videoRect.bottom)
            }
            DRAG_MOVE -> {
                val w = cropRect.width()
                val h = cropRect.height()
                val newLeft = (cropRect.left + dx).coerceIn(videoRect.left, videoRect.right - w)
                val newTop = (cropRect.top + dy).coerceIn(videoRect.top, videoRect.bottom - h)
                cropRect.offsetTo(newLeft, newTop)
            }
        }
    }

    companion object {
        private const val DRAG_NONE = 0
        private const val DRAG_TOP_LEFT = 1
        private const val DRAG_TOP = 2
        private const val DRAG_TOP_RIGHT = 3
        private const val DRAG_RIGHT = 4
        private const val DRAG_BOTTOM_RIGHT = 5
        private const val DRAG_BOTTOM = 6
        private const val DRAG_BOTTOM_LEFT = 7
        private const val DRAG_LEFT = 8
        private const val DRAG_MOVE = 9
    }
}
