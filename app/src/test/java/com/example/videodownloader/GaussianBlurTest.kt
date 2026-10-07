package com.example.videodownloader

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.DataOutputStream
import java.io.FileOutputStream
import kotlin.math.abs

class GaussianBlurTest {
    @Test fun kernelIsNormalizedAndSymmetric() {
        val k = GaussianBlur.kernel(FrameStyle.GLOW_SIGMA)
        assertEquals(1f, k.sum(), 0.00001f)
        for (i in k.indices) assertEquals(k[i], k[k.lastIndex-i], 0.000001f)
    }
    @Test fun constantImageRemainsConstantIncludingCorners() {
        val p = FloatArray(31 * 17) { 153f }
        GaussianBlur.blur(p, 31, 17, 6f)
        for (v in p) assertEquals(153f, v, 0.001f)
    }
    @Test fun verticalSplitDoesNotBorrowLeftColumn() {
        val w=100; val h=80
        val p=FloatArray(w*h) { if (it%w<50) 255f else 0f }
        GaussianBlur.blur(p,w,h,6f)
        for (y in 0 until h) {
            assertTrue(p[y*w+10]>254f)
            assertTrue(p[y*w+90]<0.01f)
            for(x in 0 until w) assertEquals(p[x],p[y*w+x],0.001f)
        }
    }
    @Test fun transposeAndBlurCommute() {
        val w=29;val h=19
        val a=FloatArray(w*h) { ((it*37)%251).toFloat() }
        val b=FloatArray(a.size) { i -> a[(i%h)*w+i/h] }
        GaussianBlur.blur(a,w,h,4f);GaussianBlur.blur(b,h,w,4f)
        for(y in 0 until h)for(x in 0 until w)
            assertEquals(a[y*w+x],b[x*h+y],0.001f)
    }
    @Test fun shadowIsMonotoneAndHasNoDetachedDarkBand() {
        val w=256;val p=FloatArray(w*3) { if(it%w>=128)255f else 0f }
        GaussianBlur.blur(p,w,3,FrameStyle.SHADOW_SIGMA_RATIO*480)
        for(x in 1..128)assertTrue("non-monotone at $x",p[x]>=p[x-1]-0.001f)
        assertTrue(p[127] in 115f..130f)
    }
    @Test fun tinyImagesAndInvalidInputs() {
        val p=floatArrayOf(42f);GaussianBlur.blur(p,1,1,22.75f)
        assertEquals(42f,p[0],0.001f)
        try { GaussianBlur.blur(FloatArray(3),2,2,1f);fail("bad dimensions accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun productionOutputMatchesIndependentDirectConvolution() {
        val w=11;val h=9;val sigma=1.6f
        val src=FloatArray(w*h) { ((it*23)%255).toFloat() };val actual=src.copyOf()
        GaussianBlur.blur(actual,w,h,sigma)
        val k=GaussianBlur.kernel(sigma);val rad=k.size/2
        for(y in 0 until h)for(x in 0 until w) {
            var expected=0.0
            for(ky in k.indices)for(kx in k.indices)
                expected+=src[(y+ky-rad).coerceIn(0,h-1)*w+(x+kx-rad).coerceIn(0,w-1)]*k[ky].toDouble()*k[kx]
            assertEquals(expected,actual[y*w+x].toDouble(),0.001)
        }
    }
    @Test fun exportProductionPixelsForPreviewComparison() {
        // Optional fixture generated from the user's reference; never required by CI.
        val dir=File("../frame-validation")
        val input=File(dir,"glow-input.rgb")
        if(!input.exists())return
        val bytes=input.readBytes(); val w=FrameStyle.GLOW_WIDTH;val h=bytes.size/(w*3)
        assertEquals(w*h*3,bytes.size)
        val channels=Array(3) { c -> FloatArray(w*h) { i -> (bytes[i*3+c].toInt() and 255).toFloat() } }
        for(c in channels)GaussianBlur.blur(c,w,h,FrameStyle.GLOW_SIGMA)
        DataOutputStream(FileOutputStream(File(dir,"production-glow.f32"))).use { out ->
            for(i in 0 until w*h)for(c in 0..2)out.writeFloat(channels[c][i])
        }
        val sw=FrameStyle.SHADOW_WIDTH;val shadow=File(dir,"shadow-input.alpha").readBytes()
        val sh=shadow.size/sw;val alpha=FloatArray(shadow.size) { (shadow[it].toInt() and 255).toFloat() }
        GaussianBlur.blur(alpha,sw,sh,FrameStyle.SHADOW_SIGMA_RATIO*sw)
        DataOutputStream(FileOutputStream(File(dir,"production-shadow.f32"))).use { out ->
            for(v in alpha)out.writeFloat(v*FrameStyle.SHADOW_OPACITY)
        }
    }
}
