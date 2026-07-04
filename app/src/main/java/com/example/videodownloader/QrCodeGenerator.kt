package com.example.videodownloader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码生成器：基于 ZXing 生成二维码 Bitmap，支持自定义美化。
 *
 * 支持的美化选项：
 * - 前景色 / 背景色
 * - 方块 / 圆点两种点阵样式
 * - 边距
 * - 容错等级（L/M/Q/H，加 Logo 建议 H）
 * - 中心 Logo（自动缩放并加白底圆角）
 *
 * 圆点样式通过在 Canvas 上画圆形实现，每个模块画一个圆，半径 = 模块边长 / 2 * 0.92。
 * Logo 叠加方式：先画白底圆角矩形，再画缩放后的 Logo Bitmap，使用 SRC_OVER 模式。
 */
object QrCodeGenerator {

    /**
     * 生成二维码 Bitmap。
     *
     * @param content 文本内容
     * @param sizePx 输出 Bitmap 边长（px），会自动取整为模块数的倍数
     * @param foreColor 前景色 ARGB
     * @param backColor 背景色 ARGB（Color.TRANSPARENT 表示透明）
     * @param margin 模块边距（0-8，对应 ZXing 的 quiet zone）
     * @param ecLevel 容错等级
     * @param roundDot true=圆点样式，false=方块样式
     * @param logo 中心 Logo Bitmap，null 表示不加
     * @param logoScale Logo 占二维码的比例（0.0-0.3），默认 0.2
     * @return 生成的 Bitmap，失败返回 null
     */
    fun generate(
        content: String,
        sizePx: Int,
        foreColor: Int = Color.BLACK,
        backColor: Int = Color.WHITE,
        margin: Int = 1,
        ecLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.H,
        roundDot: Boolean = false,
        logo: Bitmap? = null,
        logoScale: Float = 0.2f
    ): Bitmap? {
        if (content.isBlank()) return null

        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ecLevel,
                EncodeHintType.MARGIN to margin,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

            val matrixWidth = bitMatrix.width
            val matrixHeight = bitMatrix.height

            // 计算每个模块的像素大小，确保整数倍，避免边缘锯齿
            // 至少为 1px，防止低分辨率下 moduleSize=0 导致圆点画不出来
            val moduleSize = (sizePx / matrixWidth).coerceAtLeast(1)
            val renderSize = moduleSize * matrixWidth

            val bitmap = Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 1. 画背景
            if (backColor != Color.TRANSPARENT) {
                canvas.drawColor(backColor)
            } else {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }

            // 2. 画模块
            val forePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = foreColor
                style = Paint.Style.FILL
            }

            if (roundDot) {
                // 圆点样式：每个模块画一个圆，半径取模块边长的一半，稍大一点视觉更连贯
                val radius = moduleSize / 2f * 0.95f
                for (x in 0 until matrixWidth) {
                    for (y in 0 until matrixHeight) {
                        if (bitMatrix.get(x, y)) {
                            val cx = x * moduleSize + moduleSize / 2f
                            val cy = y * moduleSize + moduleSize / 2f
                            canvas.drawCircle(cx, cy, radius, forePaint)
                        }
                    }
                }
            } else {
                // 方块样式：直接画矩形
                for (x in 0 until matrixWidth) {
                    for (y in 0 until matrixHeight) {
                        if (bitMatrix.get(x, y)) {
                            val left = x * moduleSize
                            val top = y * moduleSize
                            canvas.drawRect(
                                left.toFloat(), top.toFloat(),
                                (left + moduleSize).toFloat(), (top + moduleSize).toFloat(),
                                forePaint
                            )
                        }
                    }
                }
            }

            // 3. 画 Logo
            if (logo != null && !logo.isRecycled) {
                drawLogo(canvas, bitmap, logo, logoScale)
            }

            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 在二维码中心画 Logo：白底圆角矩形 + 缩放后的 Logo。
     * Logo 面积占比 = logoScale² ，默认 0.2 表示 Logo 边长是二维码的 20%。
     */
    private fun drawLogo(canvas: Canvas, qrBitmap: Bitmap, logo: Bitmap, logoScale: Float) {
        val qrSize = qrBitmap.width
        val logoSize = (qrSize * logoScale).toInt().coerceAtLeast(1)

        // 白底，比 Logo 大 10%
        val bgSize = (logoSize * 1.1f).toInt()
        val bgLeft = (qrSize - bgSize) / 2f
        val bgTop = (qrSize - bgSize) / 2f
        val bgRect = RectF(bgLeft, bgTop, bgLeft + bgSize, bgTop + bgSize)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val radius = bgSize * 0.15f
        canvas.drawRoundRect(bgRect, radius, radius, bgPaint)

        // 缩放 Logo
        val scaledLogo = Bitmap.createScaledBitmap(logo, logoSize, logoSize, true)
        val logoLeft = (qrSize - logoSize) / 2f
        val logoTop = (qrSize - logoSize) / 2f
        val dstRect = Rect(logoLeft.toInt(), logoTop.toInt(), (logoLeft + logoSize).toInt(), (logoTop + logoSize).toInt())

        canvas.drawBitmap(scaledLogo, null, dstRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
}
