package com.example.videodownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/** One renderer for known wordmarks, fallback text and missing/long metadata. */
internal object FrameFooterRenderer {
    private data class Glyph(val text: String, val paint: Paint, val bounds: Rect) {
        val width: Float get() = bounds.width().toFloat()
        fun draw(canvas: Canvas, x: Float, top: Float, height: Float) {
            if (bounds.height() == 0 || height <= 0f) return
            canvas.save()
            canvas.translate(x, top)
            canvas.scale(1f, height / bounds.height())
            canvas.drawText(text, -bounds.left.toFloat(), -bounds.top.toFloat(), paint)
            canvas.restore()
        }
    }

    fun draw(canvas: Canvas, cw: Int, ch: Int, cardBottom: Float,
             info: PhotoInfo, context: Context, showLogo: Boolean = true,
             showParams: Boolean = true, color: Int = Color.WHITE) {
        val typeface = ResourcesCompat.getFont(context, R.font.texgyreheros_regular)
        fun paint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            this.typeface = typeface
        }
        fun glyph(text: String?, paint: Paint): Glyph? = text?.let {
            val bounds = Rect()
            paint.getTextBounds(it, 0, it.length, bounds)
            if (bounds.isEmpty) null else Glyph(it, paint, bounds)
        }
        val make = if (showLogo) CameraBrands.displayMake(info.brand) else null
        val model = if (showLogo) glyph(CameraBrands.displayModel(info.model), paint(ch * FrameFooterLayout.MODEL_EM_RATIO)) else null
        val profile = if (showLogo) CameraBrands.find(info.brand) else null
        val drawable = profile?.let {
            ContextCompat.getDrawable(context, it.wordmarkRes)?.mutate()?.apply {
                setTint(color)
            }
        }
        // Unknown brands never borrow Nikon's italic font or someone else's logo.
        val fallback = if (drawable == null) glyph(make, paint(ch * 0.023f)) else null
        val brandH = if (drawable != null || fallback != null) ch * FrameStyle.WORDMARK_HEIGHT_RATIO else 0f
        val brandW = if (drawable != null) brandH * requireNotNull(profile).aspect else fallback?.width ?: 0f
        val modelH = if (model != null) ch * FrameFooterLayout.MODEL_HEIGHT_RATIO else 0f
        val gap = if (brandW > 0 && model != null) ch * FrameStyle.BRAND_MODEL_GAP_RATIO else 0f
        val firstW = brandW + gap + (model?.width ?: 0f)
        val firstH = maxOf(brandH, modelH)

        val parts = if (showParams) PhotoLabels.params(info) else emptyList()
        val paramPaint = paint(ch * FrameFooterLayout.PARAM_EM_RATIO)
        val widths = parts.map(paramPaint::measureText)
        val tokenGap = ch * FrameFooterLayout.TOKEN_GAP_RATIO
        val paramBounds = Rect()
        val paramText = parts.joinToString(" ")
        paramPaint.getTextBounds(paramText, 0, paramText.length, paramBounds)
        val paramH = if (parts.isNotEmpty()) ch * FrameFooterLayout.PARAM_HEIGHT_RATIO else 0f
        val secondW = if (parts.isNotEmpty()) widths.sum() + tokenGap * (parts.size - 1) else 0f
        val layout = FrameFooterLayout.arrange(cw.toFloat(), ch.toFloat(), cardBottom,
            firstW, firstH, secondW, paramH)
        if (layout.scale <= 0f) return

        canvas.save()
        canvas.translate(cw / 2f, layout.top)
        canvas.scale(layout.scale, layout.scale)
        if (drawable != null) {
            canvas.save()
            canvas.translate(layout.firstX, (firstH - brandH) / 2f)
            canvas.scale(brandW / drawable.intrinsicWidth.coerceAtLeast(1),
                brandH / drawable.intrinsicHeight.coerceAtLeast(1))
            drawable.setBounds(0, 0, drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1))
            drawable.draw(canvas)
            canvas.restore()
        } else {
            fallback?.draw(canvas, layout.firstX, (firstH - brandH) / 2f, brandH)
        }
        model?.draw(canvas, layout.firstX + brandW + gap, (firstH - modelH) / 2f, modelH)
        if (parts.isNotEmpty() && paramBounds.height() > 0) {
            canvas.save()
            canvas.translate(0f, layout.secondTop)
            canvas.scale(1f, paramH / paramBounds.height())
            var x = layout.secondX
            for ((i, token) in parts.withIndex()) {
                canvas.drawText(token, x, -paramBounds.top.toFloat(), paramPaint)
                x += widths[i] + tokenGap
            }
            canvas.restore()
        }
        canvas.restore()
    }
}
