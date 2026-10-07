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
    // aliases carry raw EXIF make strings; matching is case/space/punctuation-insensitive
    // via normalize(). Profiles are ordered deliberately (see aliasIndex first-wins rule).
    val profiles = listOf(
        // ---- existing, unchanged rendering ----
        CameraBrandProfile("Nikon", setOf("nikon", "nikon corporation"),
            R.drawable.ic_camera_nikon_wordmark, FrameStyle.NIKON_ASPECT),
        CameraBrandProfile("vivo", setOf("vivo", "vivo mobile communication co., ltd."),
            R.drawable.ic_camera_vivo_wordmark, FrameStyle.VIVO_ASPECT),

        // ---- camera makers ----
        CameraBrandProfile("Canon", setOf("Canon", "CANON", "Canon Inc.", "Canon Inc",
            "Canon East Inc.", "Canon East Asia Inc."),
            R.drawable.ic_camera_canon_wordmark, 4.500658f),
        CameraBrandProfile("Fujifilm", setOf("Fujifilm", "FUJIFILM", "Fuji Photo Film",
            "FUJI PHOTO FILM CO., LTD.", "Fuji", "FUJI"),
            R.drawable.ic_camera_fujifilm_wordmark, 5.596495f),
        CameraBrandProfile("Hasselblad", setOf("Hasselblad", "HASSELBLAD", "Victor Hasselblad",
            "Hasselblad AB", "Hasselblad Film AB"),
            R.drawable.ic_camera_hasselblad_wordmark, 11.892112f),
        CameraBrandProfile("Leica", setOf("Leica", "LEICA", "Leica Camera", "LEICA CAMERA AG",
            "Leica Camera AG", "Ernst Leitz", "Leitz"),
            R.drawable.ic_camera_leica_wordmark, 1.0f),
        // Deliberate collision resolution: bare "Panasonic" must NOT fall through to Lumix.
        // Panasonic is registered first; Lumix only ever matches its explicit aliases below.
        CameraBrandProfile("Panasonic", setOf("Panasonic", "PANASONIC", "Panasonic Co.,Ltd",
            "Panasonic Corporation", "Matsushita", "Matsushita Electric"),
            R.drawable.ic_camera_panasonic_wordmark, 6.116804f),
        CameraBrandProfile("Lumix", setOf("Lumix", "LUMIX", "Panasonic Lumix", "Panasonic LUMIX"),
            R.drawable.ic_camera_lumix_wordmark, 4.839645f),
        CameraBrandProfile("Olympus", setOf("Olympus", "OLYMPUS", "Olympus Optical",
            "OLYMPUS OPTICAL CO.,LTD", "Olympus Imaging", "OLYMPUS IMAGING CORP.",
            "Olympus Corporation", "OLYMPUS CORPORATION", "OM Digital Solutions",
            "OM SYSTEM", "OM Digital"),
            R.drawable.ic_camera_olympus_wordmark, 4.974737f),
        CameraBrandProfile("Pentax", setOf("Pentax", "PENTAX", "Pentax Corporation",
            "PENTAX Corporation", "Asahi", "Asahi Optical", "Asahi Pentax",
            "Asahi Optical Co.,Ltd", "Hoya", "HOYA"),
            R.drawable.ic_camera_pentax_wordmark, 4.836277f),
        CameraBrandProfile("Ricoh", setOf("Ricoh", "RICOH", "Ricoh Company",
            "Ricoh Company, Ltd.", "RICOH COMPANY, LTD."),
            R.drawable.ic_camera_ricoh_wordmark, 5.256029f),
        CameraBrandProfile("Sony", setOf("Sony", "SONY", "Sony Corp", "Sony Corporation", "Sony Corp."),
            R.drawable.ic_camera_sony_wordmark, 5.335391f),
        CameraBrandProfile("Zeiss", setOf("Zeiss", "ZEISS", "Carl Zeiss", "CARL ZEISS",
            "Carl Zeiss AG", "CarlZeiss", "ZEISS a/s"),
            R.drawable.ic_camera_zeiss_wordmark, 3.991909f),

        // ---- phone makers ----
        CameraBrandProfile("Honor", setOf("HONOR", "Honor", "honor", "HONOR Device Co., Ltd.",
            "HONOR Device Co.,Ltd.", "HONOR Device Co., Ltd", "HONOR Device Co.,Ltd",
            "Honor Device Co., Ltd.", "Honor Device Co.,Ltd.", "HONOR Device", "HONOR/DT"),
            R.drawable.ic_camera_honor_wordmark, 5.199621f),
        CameraBrandProfile("Huawei", setOf("HUAWEI", "Huawei", "huawei",
            "HUAWEI TECHNOLOGIES CO., LTD.", "HUAWEI TECHNOLOGIES CO.,LTD.",
            "Huawei Technologies Co., Ltd.", "HUAWEI Device Co., Ltd.", "HUAWEI Device",
            "HUAWEI TECHNOLOGIES", "HUAWEI TECHNOLOGIES CO., LTD", "Huawei Device Co., Ltd."),
            R.drawable.ic_camera_huawei_wordmark, 4.168864f),
        CameraBrandProfile("iQOO", setOf("iQOO", "IQOO", "iqoo", "Iqoo", "iQOO by vivo",
            "iQOO Mobile", "IQOO Mobile Communication Corp.", "iQOO Mobile Communication Corp.",
            "vivo_iQOO", "vivoiQOO"),
            R.drawable.ic_camera_iqoo_wordmark, 4.234996f),
        CameraBrandProfile("Meizu", setOf("MEIZU", "Meizu", "meizu", "Meizu Technology Co.,Ltd.",
            "Meizu Technology Co., Ltd.", "MEIZU Technology Co., Ltd.", "Meizu.inc", "MEIZU INC",
            "Meizu Technology", "Meizu Communication Technology Co., Ltd."),
            R.drawable.ic_camera_meizu_wordmark, 5.555127f),
        CameraBrandProfile("OPPO", setOf("OPPO", "Oppo", "oppo", "OPPO Mobile",
            "Guangdong OPPO Mobile Telecommunications Corp., Ltd.",
            "OPPO Mobile Telecommunications Corp., Ltd.", "OPPO Digital",
            "Guangdong OPPO Mobile Telecommunications Corp.,Ltd."),
            R.drawable.ic_camera_oppo_wordmark, 4.220008f),
        CameraBrandProfile("OnePlus", setOf("OnePlus", "ONEPLUS", "oneplus",
            "OnePlus Technology (Shenzhen) Co.,Ltd.", "OnePlus Technology (Shenzhen) Co., Ltd.",
            "ONEPLUS Technology (Shenzhen) Co., Ltd.", "OnePlus Technology", "OnePlus Inc.",
            "OnePlus Mobile"),
            R.drawable.ic_camera_oneplus_wordmark, 4.21f),
        CameraBrandProfile("Xiaomi", setOf("Xiaomi", "XIAOMI", "xiaomi",
            "Xiaomi Communications Co., Ltd.", "XIAOMI Communications Co., Ltd.",
            "Xiaomi Communications Co.,Ltd.", "Xiaomi Inc.", "Xiaomi Redmi", "Redmi",
            "Xiaomi Mobile", "XIAOMI Redmi"),
            R.drawable.ic_camera_xiaomi_wordmark, 6.003321f),

        // ---- tech / imaging makers ----
        CameraBrandProfile("Apple", setOf("Apple", "Apple Inc.", "Apple Inc", "Apple Computer, Inc.",
            "Apple Computer Inc", "Apple Computer", "AppleComputer", "Apple Computer, Inc"),
            R.drawable.ic_camera_apple_wordmark, 0.8230830f),
        CameraBrandProfile("DJI", setOf("DJI", "DJI.", "DJI Innovations", "DJI Technology",
            "DJI Technology Co., Ltd.", "DJI Technology Co.,Ltd.", "DJI Technology Co., Ltd",
            "SZ DJI Technology Co., Ltd.", "SZ DJI Technology Co.,Ltd.", "SZ DJI Technology",
            "SZ DJI", "Dajiang Innovations", "Dajiang"),
            R.drawable.ic_camera_dji_wordmark, 1.6556479f),
        CameraBrandProfile("Insta360", setOf("Insta360", "INSTA360", "Insta 360", "Insta360 Studio",
            "Arashi Vision", "Arashi Vision Inc.", "Arashi Vision Inc", "ArashiVision",
            "Shanghai Arashi Vision", "Shanghai Arashi Vision Co., Ltd.",
            "Shanghai Arashi Network Technology Co., Ltd."),
            R.drawable.ic_camera_insta360_wordmark, 0.9813328f),
        CameraBrandProfile("Samsung", setOf("Samsung", "SAMSUNG", "Samsung Electronics",
            "SAMSUNG ELECTRONICS", "Samsung Electronics Co., Ltd.",
            "Samsung Electronics Co.,Ltd.", "Samsung Electronics Co., Ltd",
            "SamsungElectronics", "Samsung Techwin", "Samsung Techwin Co., Ltd.",
            "Samsung Camera", "Samsung Digital Camera"),
            R.drawable.ic_camera_samsung_wordmark, 6.2299895f)
    )

    // Normalized alias index. Key = normalize(alias). On an accidental duplicate key the
    // FIRST profile in `profiles` wins, which is exactly why Panasonic precedes Lumix.
    private val aliasIndex: Map<String, CameraBrandProfile> =
        profiles.fold(LinkedHashMap()) { acc, p ->
            for (a in p.aliases) normalize(a)?.let { k -> if (!acc.containsKey(k)) acc[k] = p }
            acc
        }

    fun clean(value: String?): String? = value?.replace('\u0000', ' ')
        ?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

    /** Matching key: case-insensitive, punctuation stripped, whitespace collapsed. */
    fun normalize(value: String?): String? = clean(value)
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^a-z0-9]+"), " ")
        ?.trim()?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotEmpty() }

    fun find(make: String?): CameraBrandProfile? = aliasIndex[normalize(make)]

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
