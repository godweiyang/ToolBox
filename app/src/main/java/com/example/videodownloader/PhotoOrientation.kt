package com.example.videodownloader

import androidx.exifinterface.media.ExifInterface

/** Pure EXIF-orientation mapping, separated for deterministic JVM regression tests. */
internal data class OrientationTransform(
    val rotationDegrees: Float = 0f,
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false
) {
    val changesPixels: Boolean
        get() = rotationDegrees != 0f || mirrorHorizontal || mirrorVertical
}

internal object PhotoOrientation {
    fun fromExif(value: Int): OrientationTransform = when (value) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> OrientationTransform(mirrorHorizontal = true)
        ExifInterface.ORIENTATION_ROTATE_180 -> OrientationTransform(rotationDegrees = 180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> OrientationTransform(mirrorVertical = true)
        ExifInterface.ORIENTATION_TRANSPOSE -> OrientationTransform(90f, mirrorHorizontal = true)
        ExifInterface.ORIENTATION_ROTATE_90 -> OrientationTransform(rotationDegrees = 90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> OrientationTransform(270f, mirrorHorizontal = true)
        ExifInterface.ORIENTATION_ROTATE_270 -> OrientationTransform(rotationDegrees = 270f)
        else -> OrientationTransform()
    }
}
