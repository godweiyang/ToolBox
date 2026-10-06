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

    fun compose(src: Bitmap, info: PhotoInfo): Bitmap {
        // 1) 输出画布（保持原图宽高比，长边 1800）
        val scale = LONG_EDGE.toFloat() / max(src.width, src.height)
        val cw = max(2, (src.width * scale).roundToInt())
        val ch = max(2, (src.height * scale).roundToInt())
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        // 2) 版式：边距与底部文字带
        val side = cw * 0.064f
        val topInset = ch * 0.044f
        val bandH = ch * 0.085f
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
        val cardRadius = min(cardW, cardH) * 0.024f

        // 3) 光晕：照片放大铺满后重度模糊，外缘裁成大圆角
        drawGlow(canvas, cw, ch, src)

        // 4) 卡片阴影
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
            drawTexts(canvas, cw, ch, cardRect.bottom, bandH, info)
        }
        return out
    }

    /**
     * 光晕：照片缩放到 400px 宽铺满画布后重度模糊（保留各边缘真实颜色分布），
     * 再用大圆角 alpha 蒙版裁掉外圆角，最后放大铺到画布上。
     */
    private fun drawGlow(canvas: Canvas, cw: Int, ch: Int, src: Bitmap) {
        val gw = 400
        val gh = (ch.toFloat() * gw / cw).roundToInt().coerceAtLeast(2)
        val glow = Bitmap.createScaledBitmap(src, gw, gh, true)
            .copy(Bitmap.Config.ARGB_8888, true)

        val px = IntArray(gw * gh)
        glow.getPixels(px, 0, gw, 0, 0, gw, gh)
        StackBlur.blur(px, gw, gh, 18)

        // 大圆角蒙版（半径 4.5% 宽）
        val mask = Bitmap.createBitmap(gw, gh, Bitmap.Config.ALPHA_8)
        val mc = Canvas(mask)
        val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val mr = gw * 0.045f
        mc.drawRoundRect(RectF(0f, 0f, gw.toFloat(), gh.toFloat()), mr, mr, mp)
        val mpx = IntArray(gw * gh)
        mask.getPixels(mpx, 0, gw, 0, 0, gw, gh)
        for (i in px.indices) {
            val a = (mpx[i] shr 24) and 0xff
            px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
        }
        glow.setPixels(px, 0, gw, 0, 0, gw, gh)

        val up = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(glow, null, RectF(0f, 0f, cw.toFloat(), ch.toFloat()), up)
    }

    /** 卡片外一圈柔和黑色阴影。 */
    private fun drawCardShadow(
        canvas: Canvas, cw: Int, ch: Int, card: RectF, cardRadius: Float
    ) {
        val sw = 480
        val sh = (ch.toFloat() * sw / cw).roundToInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val kx = sw.toFloat() / cw
        val ky = sh.toFloat() / ch
        val spread = sw * 0.025f
        val rect = RectF(
            card.left * kx - spread,
            card.top * ky - spread,
            card.right * kx + spread,
            card.bottom * ky + spread
        )
        val radius = cardRadius * kx + spread * 0.6f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 130
        }
        c.drawRoundRect(rect, radius, radius, paint)

        val px = IntArray(sw * sh)
        bmp.getPixels(px, 0, sw, 0, 0, sw, sh)
        StackBlur.blur(px, sw, sh, (sw * 0.03f).roundToInt())
        bmp.setPixels(px, 0, sw, 0, 0, sw, sh)

        val up = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bmp, null, RectF(0f, 0f, cw.toFloat(), ch.toFloat()), up)
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

    /** 底部两行白字：品牌（斜体粗）+ 型号；拍摄参数。 */
    private fun drawTexts(
        canvas: Canvas, cw: Int, ch: Int, cardBottom: Float, bandH: Float, info: PhotoInfo
    ) {
        val white = Color.WHITE

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = bandH * 0.30f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC
            )
        }
        val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = bandH * 0.27f
            letterSpacing = 0.08f
        }
        val paramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = bandH * 0.24f
            letterSpacing = 0.05f
        }

        // 第一行
        val brand = info.brand
        val model = info.model
        val brandW = if (brand != null) brandPaint.measureText(brand) else 0f
        val modelW = if (model != null) modelPaint.measureText(model) else 0f
        val gap1 = if (brand != null && model != null) brandPaint.textSize * 0.55f else 0f
        val line1W = brandW + gap1 + modelW

        // 第二行
        val parts = buildList {
            info.focalMm?.let { add(fmtFocal(it)) }
            info.fNumber?.let { add("F${trimNumber(it)}") }
            info.exposureSec?.let { add(fmtShutter(it)) }
            info.iso?.let { add("ISO$it") }
        }
        val line2 = parts.joinToString("   ")
        val line2W = if (line2.isNotEmpty()) paramPaint.measureText(line2) else 0f

        // 垂直居中于底部黑带
        val fm1 = if (line1W > 0f) brandPaint.fontMetrics else paramPaint.fontMetrics
        val fm2 = paramPaint.fontMetrics
        val line1H = fm1.descent - fm1.ascent
        val line2H = if (line2W > 0f) fm2.descent - fm2.ascent else 0f
        val gap2 = if (line1W > 0f && line2W > 0f) bandH * 0.14f else 0f
        val totalH = line1H + gap2 + line2H
        val bandTop = cardBottom
        val blockTop = bandTop + (bandH - totalH) / 2f

        if (line1W > 0f) {
            var x = (cw - line1W) / 2f
            val baseline = blockTop - fm1.ascent
            if (brand != null) {
                canvas.drawText(brand, x, baseline, brandPaint)
                x += brandW + gap1
            }
            if (model != null) {
                canvas.drawText(model, x, baseline + (brandPaint.textSize - modelPaint.textSize) * 0.0f, modelPaint)
            }
        }
        if (line2W > 0f) {
            val x = (cw - line2W) / 2f
            val baseline = blockTop + line1H + gap2 - fm2.ascent
            canvas.drawText(line2, x, baseline, paramPaint)
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
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]

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
