package com.example.videodownloader

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Brand assets are independent of model names and footer typography. */
internal data class CameraBrandProfile(
    val name: String,
    val aliases: Set<String>,
    val wordmarkRes: Int,
    val aspect: Float
)

internal object CameraBrands {
    // Add a vetted asset and aliases here; do not add a new rendering branch per device.
    val profiles = listOf(
        CameraBrandProfile("Nikon", setOf("nikon", "nikon corporation"),
            R.drawable.ic_camera_nikon_wordmark, FrameStyle.NIKON_ASPECT),
        CameraBrandProfile("vivo", setOf("vivo", "vivo mobile communication co., ltd."),
            R.drawable.ic_camera_vivo_wordmark, FrameStyle.VIVO_ASPECT)
    )

    fun clean(value: String?): String? = value?.replace('\u0000', ' ')
        ?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

    fun find(make: String?): CameraBrandProfile? {
        val key = clean(make)?.lowercase(Locale.ROOT) ?: return null
        return profiles.firstOrNull { key in it.aliases }
    }

    fun displayMake(make: String?): String? = find(make)?.name ?: clean(make)

    // EXIF is the authority. Unknown codes are not guessed into commercial model names.
    fun displayModel(model: String?): String? = clean(model)
}

/** Locale-independent formatting. No one-decimal rounding of aperture values. */
internal object PhotoLabels {
    fun number(value: Double): String {
        if (!value.isFinite() || value <= 0.0) return ""
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
    }

    fun focal(value: Double): String {
        if (!value.isFinite() || value <= 0.0) return ""
        val rounded = value.roundToInt()
        return if (abs(value - rounded) < 0.05) "${rounded}mm"
        else "${number(value)}mm"
    }

    fun shutter(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0.0) return ""
        if (seconds >= 1.0) return "${number(seconds)}s"
        val denominator = 1.0 / seconds
        return if (denominator.isFinite() && denominator <= Int.MAX_VALUE)
            "1/${denominator.roundToInt()}s" else ""
    }

    fun params(info: PhotoInfo): List<String> = buildList {
        info.focalMm?.let { focal(it).takeIf(String::isNotEmpty)?.let(::add) }
        info.fNumber?.let { number(it).takeIf(String::isNotEmpty)?.let { add("F$it") } }
        info.exposureSec?.let { shutter(it).takeIf(String::isNotEmpty)?.let(::add) }
        info.iso?.takeIf { it > 0 }?.let { add("ISO$it") }
    }
}

/** Pure geometry: every brand shares positioning, fit-to-width and missing-data behavior. */
internal data class FooterLayout(
    val scale: Float,
    val top: Float,
    val firstX: Float,
    val secondX: Float,
    val firstHeight: Float,
    val secondTop: Float,
    val totalHeight: Float
)

internal object FrameFooterLayout {
    const val MODEL_EM_RATIO = 0.016f
    const val PARAM_EM_RATIO = 20.5f / 1442f
    const val MODEL_HEIGHT_RATIO = 18f / 1442f
    const val PARAM_HEIGHT_RATIO = 17f / 1442f
    const val TOKEN_GAP_RATIO = 7.5f / 1442f

    fun arrange(
        width: Float, height: Float, cardBottom: Float,
        firstWidth: Float, firstHeight: Float, secondWidth: Float, secondHeight: Float
    ): FooterLayout {
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
        require(listOf(firstWidth, firstHeight, secondWidth, secondHeight).all { it.isFinite() && it >= 0 })
        val lineGap = if (firstHeight > 0 && secondHeight > 0)
            height * FrameStyle.FOOTER_LINE_GAP_RATIO else 0f
        val totalHeight = firstHeight + lineGap + secondHeight
        val bottom = height * (1f - FrameStyle.FOOTER_BOTTOM_RATIO)
        val availableHeight = (bottom - cardBottom - height * 0.008f).coerceAtLeast(0f)
        val widest = maxOf(firstWidth, secondWidth)
        val fitWidth = if (widest > 0) width * (1f - 2f * 0.0597f) / widest else 1f
        val fitHeight = if (totalHeight > 0) availableHeight / totalHeight else 1f
        val scale = minOf(1f, fitWidth, fitHeight).coerceAtLeast(0f)
        return FooterLayout(scale, bottom - totalHeight * scale,
            -firstWidth / 2f, -secondWidth / 2f, firstHeight,
            firstHeight + lineGap, totalHeight)
    }
}
