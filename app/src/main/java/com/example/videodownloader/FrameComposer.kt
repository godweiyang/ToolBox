package com.example.videodownloader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 从照片 EXIF 中解析出的拍摄信息。
 */
data class PhotoInfo(
    val brand: String?,       // Nikon
    val model: String?,       // Z 30
    val focalMm: Double?,     // 375
    val fNumber: Double?,     // 11
    val exposureSec: Double?, // 0.005 (1/200)
    val iso: Int?             // 100
) {
    val hasCamera: Boolean get() = brand != null || model != null
    val hasParams: Boolean
        get() = focalMm != null || fNumber != null || exposureSec != null || iso != null
    val hasAny: Boolean get() = hasCamera || hasParams
}

/**
 * 光影边框合成器：
 *  - 黑色画布
 *  - 照片边缘取色，生成大圆角柔和光晕
 *  - 照片以圆角卡片悬浮，卡片周围有柔和阴影
 *  - 底部居中叠加品牌型号 + 拍摄参数
 */
object FrameComposer {

    private const val LONG_EDGE = 1800

    fun compose(src: Bitmap, info: PhotoInfo, context: android.content.Context): Bitmap {
        // 1) 输出画布（保持原图宽高比，长边 1800）
        val scale = LONG_EDGE.toFloat() / max(src.width, src.height)
        val cw = max(2, (src.width * scale).roundToInt())
        val ch = max(2, (src.height * scale).roundToInt())
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        // 2) 版式：边距与底部文字带（按参考成品模板匹配实测）
        val side = cw * 0.0597f
        val topInset = ch * 0.0402f
        val bandH = ch * 0.0792f
        val contentL = side
        val contentR = cw - side
        val contentT = topInset
        val contentB = ch - bandH
        val availW = contentR - contentL
        val availH = contentB - contentT

        val sFit = min(availW / src.width, availH / src.height)
        val cardW = src.width * sFit
        val cardH = src.height * sFit
        val cardL = (cw - cardW) / 2f
        val cardT = contentT + (availH - cardH) / 2f
        val cardRect = RectF(cardL, cardT, cardL + cardW, cardT + cardH)
        val cardRadius = min(cardW, cardH) * 0.026f

        // 3) 光晕：照片铺满后进行 Gaussian 模糊，保留各侧颜色分布
        drawGlow(canvas, cw, ch, src)

        // 4) 实体接触阴影，归一化 Gaussian 连续衰减
        drawCardShadow(canvas, cw, ch, cardRect, cardRadius)

        // 5) 圆角照片卡片
        val scaled = Bitmap.createScaledBitmap(
            src, cardW.roundToInt().coerceAtLeast(1),
            cardH.roundToInt().coerceAtLeast(1), true
        )
        val cardBmp = roundCorners(scaled, cardRadius)
        canvas.drawBitmap(cardBmp, cardRect.left, cardRect.top, null)

        // 6) 底部文字
        if (info.hasAny) {
            val brandTf = androidx.core.content.res.ResourcesCompat
                .getFont(context, R.font.texgyreheros_bolditalic)
            val regTf = androidx.core.content.res.ResourcesCompat
                .getFont(context, R.font.texgyreheros_regular)
            val nikon = if (info.brand?.equals("Nikon", ignoreCase = true) == true)
                androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_camera_nikon_wordmark)
            else null
            drawTexts(canvas, cw, ch, cardRect.bottom, bandH, info, brandTf, regTf, nikon)
        }
        return out
    }

    /**
     * 光晕：照片缩放到 400px 宽铺满画布后重度模糊（保留各边缘真实颜色分布），
     * 全画布矩形铺满（不裁外圆角），再放大铺到画布上。
     */
    private fun drawGlow(canvas: Canvas, cw: Int, ch: Int, src: Bitmap) {
        val gw = FrameStyle.GLOW_WIDTH
        val gh = (ch.toFloat() * gw / cw).roundToInt().coerceAtLeast(2)
        val glow = Bitmap.createScaledBitmap(src, gw, gh, true)
            .copy(Bitmap.Config.ARGB_8888, true)

        val px = IntArray(gw * gh)
        glow.getPixels(px, 0, gw, 0, 0, gw, gh)
        // Gaussian sigma is not interchangeable with a StackBlur radius.
        val output = IntArray(px.size) { Color.BLACK }
        for (shift in intArrayOf(16, 8, 0)) {
            val channel = FloatArray(px.size) { ((px[it] shr shift) and 0xff).toFloat() }
            GaussianBlur.blur(channel, gw, gh, FrameStyle.GLOW_SIGMA)
            for (i in output.indices) {
                output[i] = output[i] or (channel[i].roundToInt().coerceIn(0, 255) shl shift)
            }
        }
        glow.setPixels(output, 0, gw, 0, 0, gw, gh)

        // 光晕全画布铺满（不裁外圆角，保证四角为照片边缘真实颜色）
        val up = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(glow, null, RectF(0f, 0f, cw.toFloat(), ch.toFloat()), up)
        glow.recycle()
    }

    /** 实体接触阴影：边缘最暗，Gaussian 连续衰减；按画布比例缩放。 */
    private fun drawCardShadow(
        canvas: Canvas, cw: Int, ch: Int, card: RectF, cardRadius: Float
    ) {
        val sw = FrameStyle.SHADOW_WIDTH
        val sh = (ch.toFloat() * sw / cw).roundToInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val kx = sw.toFloat() / cw
        val ky = sh.toFloat() / ch
        Canvas(bmp).drawRoundRect(
            RectF(card.left * kx, card.top * ky, card.right * kx, card.bottom * ky),
            cardRadius * kx, cardRadius * ky,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        )
        val px = IntArray(sw * sh)
        bmp.getPixels(px, 0, sw, 0, 0, sw, sh)
        val alpha = FloatArray(px.size) { (px[it] ushr 24).toFloat() }
        GaussianBlur.blur(alpha, sw, sh, FrameStyle.SHADOW_SIGMA_RATIO * sw)
        for (i in px.indices) {
            px[i] = (alpha[i] * FrameStyle.SHADOW_OPACITY).roundToInt()
                .coerceIn(0, 255) shl 24
        }
        bmp.setPixels(px, 0, sw, 0, 0, sw, sh)
        canvas.drawBitmap(bmp, null, RectF(0f, 0f, cw.toFloat(), ch.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        bmp.recycle()
    }

    /** 把位图裁成圆角。 */
    private fun roundCorners(bmp: Bitmap, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
        return out
    }

    /**
     * 底部两行白字：品牌（粗斜体）+ 型号；拍摄参数。
     * 字体为 TeX Gyre Heros（Helvetica 克隆），字号/间距按参考成品实测。
     */
    private fun drawTexts(
        canvas: Canvas, cw: Int, ch: Int, cardBottom: Float, bandH: Float,
        info: PhotoInfo,
        brandTf: android.graphics.Typeface?,
        regTf: android.graphics.Typeface?,
        nikonWordmark: android.graphics.drawable.Drawable?
    ) {
        val brandEm = ch * 0.0250f
        val modelEm = ch * 0.0160f
        val paramEm = ch * if (nikonWordmark != null) (20.5f / 1442f) else 0.0132f

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = brandEm
            typeface = brandTf
        }
        val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = modelEm
            typeface = regTf
        }
        val paramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = paramEm
            typeface = regTf
        }

        // 第一行：品牌 + 型号
        val brand = info.brand
        val model = info.model
        val brandW = if (brand != null) brandPaint.measureText(brand) else 0f
        val modelW = if (model != null) modelPaint.measureText(model) else 0f
        val gap1 = if (brand != null && model != null) ch * 0.0180f else 0f
        val line1W = brandW + gap1 + modelW

        // 第二行：逐 token 绘制，token 间距 = 参数 em × 0.6
        val parts = buildList {
            info.focalMm?.let { add(fmtFocal(it)) }
            info.fNumber?.let { add("F${trimNumber(it)}") }
            info.exposureSec?.let { add(fmtShutter(it)) }
            info.iso?.let { add("ISO$it") }
        }
        val partWidths = parts.map { paramPaint.measureText(it) }
        val tokenGap = if (nikonWordmark != null) ch * (7.5f / 1442f) else paramEm * 0.6f
        val line2W = if (parts.isNotEmpty())
            partWidths.sum() + tokenGap * (parts.size - 1) else 0f

        // Nikon uses an actual vector wordmark, not an approximation made with a font.
        // Align visible glyph bounds rather than font ascent/descent (which vary by platform).
        if (nikonWordmark != null) {
            val logoH = ch * FrameStyle.NIKON_HEIGHT_RATIO
            val logoW = logoH * FrameStyle.NIKON_ASPECT
            val modelBounds = android.graphics.Rect()
            if (model != null) modelPaint.getTextBounds(model, 0, model.length, modelBounds)
            val paramBounds = android.graphics.Rect()
            val parameterText = parts.joinToString(" ")
            if (parameterText.isNotEmpty())
                paramPaint.getTextBounds(parameterText, 0, parameterText.length, paramBounds)
            val paramH = if (parts.isNotEmpty()) ch * (17f / 1442f) else 0f
            val modelH = if (model != null) ch * (18f / 1442f) else 0f
            val modelVisibleW = if (model != null) modelBounds.width().toFloat() else 0f
            val gap = if (model != null) ch * FrameStyle.BRAND_MODEL_GAP_RATIO else 0f
            val firstW = logoW + gap + modelVisibleW
            val lineGap = if (parts.isNotEmpty()) ch * FrameStyle.FOOTER_LINE_GAP_RATIO else 0f
            val footerBottom = ch - ch * FrameStyle.FOOTER_BOTTOM_RATIO
            val logoTop = footerBottom - paramH - lineGap - logoH
            val firstX = (cw - firstW) / 2f
            canvas.save()
            canvas.translate(firstX, logoTop)
            canvas.scale(logoW / 990f, logoH / 250f)
            nikonWordmark.setBounds(0, 0, 990, 250)
            nikonWordmark.draw(canvas)
            canvas.restore()
            if (model != null && modelBounds.height() > 0) {
                canvas.save()
                canvas.translate(firstX + logoW + gap, logoTop + (logoH - modelH) / 2f)
                canvas.scale(1f, modelH / modelBounds.height())
                canvas.drawText(model, -modelBounds.left.toFloat(), -modelBounds.top.toFloat(), modelPaint)
                canvas.restore()
            }
            if (parts.isNotEmpty() && paramBounds.height() > 0) {
                canvas.save()
                canvas.translate(0f, footerBottom - paramH)
                canvas.scale(1f, paramH / paramBounds.height())
                var x = (cw - line2W) / 2f
                for ((i, token) in parts.withIndex()) {
                    canvas.drawText(token, x, -paramBounds.top.toFloat(), paramPaint)
                    x += partWidths[i] + tokenGap
                }
                canvas.restore()
            }
            return
        }

        // 视觉块垂直居中于底部黑带
        val l1vis = brandEm * 0.76f
        val l2vis = paramEm * 0.73f
        val gap2 = if (line1W > 0f && line2W > 0f) ch * 0.0139f else 0f
        val block = l1vis + gap2 + l2vis
        val btop = cardBottom + (bandH - block) / 2f

        if (line1W > 0f) {
            val baseline = btop + l1vis * 0.80f
            var x = (cw - line1W) / 2f
            if (brand != null) {
                canvas.drawText(brand, x, baseline, brandPaint)
                x += brandW + gap1
            }
            if (model != null) {
                canvas.drawText(model, x, baseline, modelPaint)
            }
        }
        if (line2W > 0f) {
            val baseline = btop + l1vis + gap2 + l2vis * 0.82f
            var x = (cw - line2W) / 2f
            for ((i, t) in parts.withIndex()) {
                canvas.drawText(t, x, baseline, paramPaint)
                x += partWidths[i] + tokenGap
            }
        }
    }

    fun fmtFocal(v: Double): String {
        val r = v.roundToInt().toDouble()
        return if (abs(v - r) < 0.05) "${r.roundToInt()}mm"
        else String.format("%.1fmm", v)
    }

    fun trimNumber(v: Double): String {
        if (abs(v - v.roundToInt()) < 0.001) return v.roundToInt().toString()
        val s = String.format("%.1f", v).trimEnd('0').trimEnd('.')
        return s
    }

    fun fmtShutter(sec: Double): String = when {
        sec >= 1f -> "${trimNumber(sec)}s"
        else -> "1/${(1.0 / sec).roundToInt()}s"
    }
}

/**
 * Stack Blur (Mario Klingemann)，纯 Kotlin 实现。
 * 输入 ARGB 像素数组，原地模糊；alpha 通道保持不透明。
 */
object StackBlur {

    fun blur(pix: IntArray, w: Int, h: Int, radiusInput: Int) {
        val radius = min(radiusInput, 253)
        if (radius < 1) return
        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int; var gsum: Int; var bsum: Int
        var x: Int; var y: Int; var i: Int; var p: Int
        var yp: Int; var yi: Int; var yw: Int
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        i = 0
        while (i < 256 * divsum) { dv[i] = i / divsum; i++ }

        yw = 0; yi = 0
        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int; var goutsum: Int; var boutsum: Int
        var rinsum: Int; var ginsum: Int; var binsum: Int

        y = 0
        while (y < h) {
            bsum = 0; gsum = bsum; rsum = gsum
            boutsum = rsum; goutsum = boutsum; routsum = goutsum
            binsum = routsum; ginsum = binsum; rinsum = ginsum
            i = -radius
            while (i <= radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p shr 16) and 0xff
                sir[1] = (p shr 8) and 0xff
                sir[2] = p and 0xff
                rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
                i++
            }
            stackpointer = radius
            x = 0
            while (x < w) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum]

                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (y == 0) vmin[x] = min(x + radius + 1, wm)
                p = pix[yw + vmin[x]]

                sir[0] = (p shr 16) and 0xff
                sir[1] = (p shr 8) and 0xff
                sir[2] = p and 0xff

                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi++
                x++
            }
            yw += w
            y++
        }
        x = 0
        while (x < w) {
            bsum = 0; gsum = bsum; rsum = gsum
            boutsum = rsum; goutsum = boutsum; routsum = goutsum
            binsum = routsum; ginsum = binsum; rinsum = ginsum
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                rbs = r1 - abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
                if (i < hm) yp += w
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (0xff000000.toInt() and pix[yi]) or
                        (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (x == 0) vmin[y] = min(y + r1, hm) * w
                p = vmin[y]
                sir[0] = r[p + x]; sir[1] = g[p + x]; sir[2] = b[p + x]

                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi += w
                y++
            }
            x++
        }
    }
}

/**
 * 可分离 box blur（单通道灰度，整数滑动窗口）。
 * 窗口大小 2r+1，边缘用边缘像素填充；先横向后纵向，原地输出。
 */
object FastBoxBlur {

    fun blur(pix: IntArray, w: Int, h: Int, r: Int) {
        if (r < 1) return
        val win = r + r + 1
        val tmp = IntArray(w * h)

        // 横向
        for (y in 0 until h) {
            val o = y * w
            var acc = pix[o] * (r + 1)
            for (x in 1..r) acc += pix[o + min(x, w - 1)]
            for (x in 0 until w) {
                tmp[o + x] = acc / win
                acc -= pix[o + max(0, x - r)]
                acc += pix[o + min(w - 1, x + r + 1)]
            }
        }

        // 纵向
        for (x in 0 until w) {
            var acc = tmp[x] * (r + 1)
            for (y in 1..r) acc += tmp[min(y, h - 1) * w + x]
            for (y in 0 until h) {
                pix[y * w + x] = acc / win
                acc -= tmp[max(0, y - r) * w + x]
                acc += tmp[min(h - 1, y + r + 1) * w + x]
            }
        }
    }
}
