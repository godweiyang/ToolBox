package com.example.videodownloader

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test

class PhotoOrientationTest {
    @Test fun normalAndUndefinedDoNothing() {
        assertEquals(OrientationTransform(), PhotoOrientation.fromExif(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(OrientationTransform(), PhotoOrientation.fromExif(ExifInterface.ORIENTATION_UNDEFINED))
    }

    @Test fun rotationsMatchExifContract() {
        assertEquals(90f, PhotoOrientation.fromExif(ExifInterface.ORIENTATION_ROTATE_90).rotationDegrees, 0f)
        assertEquals(180f, PhotoOrientation.fromExif(ExifInterface.ORIENTATION_ROTATE_180).rotationDegrees, 0f)
        assertEquals(270f, PhotoOrientation.fromExif(ExifInterface.ORIENTATION_ROTATE_270).rotationDegrees, 0f)
    }

    @Test fun mirroredOrientationsArePreserved() {
        val horizontal = PhotoOrientation.fromExif(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        assertTrue(horizontal.mirrorHorizontal)
        val vertical = PhotoOrientation.fromExif(ExifInterface.ORIENTATION_FLIP_VERTICAL)
        assertTrue(vertical.mirrorVertical)
        val transpose = PhotoOrientation.fromExif(ExifInterface.ORIENTATION_TRANSPOSE)
        assertTrue(transpose.mirrorHorizontal)
        assertEquals(90f, transpose.rotationDegrees, 0f)
        val transverse = PhotoOrientation.fromExif(ExifInterface.ORIENTATION_TRANSVERSE)
        assertTrue(transverse.mirrorHorizontal)
        assertEquals(270f, transverse.rotationDegrees, 0f)
    }

    @Test fun onlyRealTransformsChangePixels() {
        assertFalse(PhotoOrientation.fromExif(ExifInterface.ORIENTATION_NORMAL).changesPixels)
        assertTrue(PhotoOrientation.fromExif(ExifInterface.ORIENTATION_ROTATE_90).changesPixels)
    }
}
