package com.example.videodownloader.chart

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A marker screen position on the skyplot (framework-free value). */
data class SkyPoint(val sx: Float, val sy: Float)

/**
 * Pure polar geometry for the GNSS skyplot (formerly inlined in SkyplotView).
 *
 * Invariants preserved:
 *  - center = zenith (elevation 90), outer ring = horizon (elevation 0).
 *  - r = (1 - elevation/90) * maxR
 *  - sx = cx + r*sin(az), sy = cy - r*cos(az) with azimuth in degrees,
 *    N(0deg)=up, E(90deg)=right, S(180deg)=down, W(270deg)=left.
 *  - C/N0 radius: clamp to [15,50], map linearly to [5,12] px.
 */
class SkyGeometry(
    val cx: Float,
    val cy: Float,
    val maxR: Float
) {
    fun position(azimuthDeg: Float, elevationDeg: Float): SkyPoint {
        val r = (1f - elevationDeg / 90f) * maxR
        val a = Math.toRadians(azimuthDeg.toDouble())
        val sx = cx + (r * sin(a)).toFloat()
        val sy = cy - (r * cos(a)).toFloat()
        return SkyPoint(sx, sy)
    }

    fun satRadius(cn0: Float): Float {
        val c = cn0.coerceIn(15f, 50f)
        return 5f + (c - 15f) / 35f * 7f
    }

    /** Horizon, 30deg ring, 60deg ring radii (outer -> inner). */
    fun ringRadii(): FloatArray = floatArrayOf(maxR, maxR * 2f / 3f, maxR / 3f)

    /** Nearest marker within [hitRadius], or -1. */
    fun nearest(points: List<SkyPoint>, touchX: Float, touchY: Float, hitRadius: Float): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in points.indices) {
            val d = hypot(touchX - points[i].sx, touchY - points[i].sy)
            if (d < bestDist && d < hitRadius) {
                bestDist = d
                best = i
            }
        }
        return best
    }
}
