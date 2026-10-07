package com.example.videodownloader

import org.junit.Assert.*
import org.junit.Test

class CameraBrandStyleTest {
    @Test fun knownBrandsResolveRegardlessOfCaseAndSpacing() {
        assertEquals("Nikon", CameraBrands.find(" NIKON ")?.name)
        assertEquals("vivo", CameraBrands.find("vivo")?.name)
        assertEquals("vivo", CameraBrands.find("VIVO MOBILE COMMUNICATION CO., LTD.")?.name)
    }

    @Test fun unknownBrandIsPreservedAndNeverMappedToSomeoneElse() {
        assertNull(CameraBrands.find("Future Camera"))
        assertEquals("Future Camera", CameraBrands.displayMake(" Future   Camera "))
    }

    @Test fun modelKeepsExifCaseAndNormalizesWhitespace() {
        assertEquals("vivo X200 Pro", CameraBrands.displayModel(" vivo   X200 Pro "))
        assertEquals("NIKON Z 30", CameraBrands.displayModel("NIKON Z 30"))
    }

    @Test fun parametersPreserveUsefulPrecision() {
        val info=PhotoInfo("vivo","vivo X200 Pro",46.0,1.57,1.0/33.0,585)
        assertEquals(listOf("46mm","F1.57","1/33s","ISO585"),PhotoLabels.params(info))
        assertEquals("F11", "F${PhotoLabels.number(11.0)}")
    }

    @Test fun invalidNumbersAreNotRendered() {
        val info=PhotoInfo(null,null,Double.NaN,-1.0,0.0,-2)
        assertTrue(PhotoLabels.params(info).isEmpty())
    }

    @Test fun footerLayoutFitsLongMetadataIntoSafeWidth() {
        val result=FrameFooterLayout.arrange(1080f,1800f,1600f,1600f,40f,1800f,30f)
        assertTrue(result.scale in 0f..1f)
        assertTrue(1800f*result.scale <= 1080f*(1f-2f*.0597f)+.01f)
        assertTrue(result.top >= 1600f)
    }

    @Test fun missingLinesDoNotLeavePhantomGap() {
        val onlyParams=FrameFooterLayout.arrange(1080f,1800f,1600f,0f,0f,300f,30f)
        assertEquals(30f,onlyParams.totalHeight,.001f)
        val onlyLogo=FrameFooterLayout.arrange(1080f,1800f,1600f,300f,30f,0f,0f)
        assertEquals(30f,onlyLogo.totalHeight,.001f)
    }

    @Test fun frameOptionsDefaultsPreservePublishedRendering() {
        assertEquals(FrameRatio.ORIGINAL,FrameOptions().ratio)
        assertEquals(FrameTheme.PHOTO,FrameOptions().theme)
        assertTrue(FrameOptions().showLogo)
        assertTrue(FrameOptions().showParams)
        assertEquals(1f,FrameOptions().shadow,0f)
        assertEquals(1f,FrameOptions().margin,0f)
    }
}
