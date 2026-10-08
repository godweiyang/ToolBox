package com.example.videodownloader.chart

/**
 * WiFi RSSI -> heat color ramp and signal-level labels.
 *
 * Invariant (preserved from HeatMapView):
 *   t = ((rssi + 90) / 60).coerceIn(0, 1)
 *   t < 0.5  -> lerp(RED   0xE53935, YELLOW 0xFDD835, t*2)
 *   t >= 0.5 -> lerp(YELLOW 0xFDD835, GREEN 0x43A047, (t-0.5)*2)
 * i.e. -90 dBm = red, -60 dBm = yellow (split point), -30 dBm = green.
 *
 * Framework-free so JUnit can pin every endpoint and the monotonic ramp.
 */
object RssiScale {

    private const val WEAK_RGB = 0xE53935  // red
    private const val MID_RGB = 0xFDD835    // yellow
    private const val STRONG_RGB = 0x43A047 // green

    private fun opaque(rgb: Int): Int =
        Argb.pack(255, Argb.red(rgb), Argb.green(rgb), Argb.blue(rgb))

    fun normalized(rssi: Int): Float = ((rssi + 90) / 60f).coerceIn(0f, 1f)

    fun color(rssi: Int): Int {
        val t = normalized(rssi)
        return if (t < 0.5f) {
            Argb.lerp(opaque(WEAK_RGB), opaque(MID_RGB), t * 2f)
        } else {
            Argb.lerp(opaque(MID_RGB), opaque(STRONG_RGB), (t - 0.5f) * 2f)
        }
    }

    fun level(rssi: Int): String = when {
        rssi >= -55 -> "极佳"
        rssi >= -65 -> "良好"
        rssi >= -75 -> "一般"
        rssi >= -85 -> "较弱"
        else -> "很差"
    }
}
