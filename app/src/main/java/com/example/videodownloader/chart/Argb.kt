package com.example.videodownloader.chart

/**
 * Framework-free ARGB color math. Mirrors the bit manipulation previously inlined in the
 * chart views, but without depending on android.graphics.Color so it runs under plain JUnit.
 *
 * A color int is packed as [A:8][R:8][G:8][B:8] (top byte first).
 */
object Argb {

    fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or
                ((r and 0xFF) shl 16) or
                ((g and 0xFF) shl 8) or
                (b and 0xFF)

    fun alpha(c: Int): Int = (c ushr 24) and 0xFF
    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF

    /** Linear interpolation of every channel (alpha included) between two color ints. */
    fun lerp(c1: Int, c2: Int, tIn: Float): Int {
        val t = tIn.coerceIn(0f, 1f)
        return pack(
            (alpha(c1) + (alpha(c2) - alpha(c1)) * t).toInt(),
            (red(c1) + (red(c2) - red(c1)) * t).toInt(),
            (green(c1) + (green(c2) - green(c1)) * t).toInt(),
            (blue(c1) + (blue(c2) - blue(c1)) * t).toInt()
        )
    }

    /** Keep RGB, replace the alpha channel (0..255). */
    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
}
