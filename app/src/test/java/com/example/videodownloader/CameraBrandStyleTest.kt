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

    // ---- v1.9.23: 24 integrated brand wordmarks ----

    @Test fun exactly24BrandsAreRegistered() {
        assertEquals(24, CameraBrands.profiles.size)
    }

    @Test fun everyRegisteredBrandResolvesByItsCanonicalName() {
        // bare canonical display name, in a few casings
        for (p in CameraBrands.profiles) {
            val viaCanonical = CameraBrands.find(p.name)
            assertNotNull("canonical name did not resolve for ${p.name}", viaCanonical)
            assertEquals(p.name, viaCanonical?.name)
            assertEquals(p.name, CameraBrands.find(p.name.uppercase())?.name)
            assertEquals(p.name, CameraBrands.find(p.name.lowercase())?.name)
            // whitespace padding must not matter
            assertEquals(p.name, CameraBrands.find("  ${p.name}   ")?.name)
        }
    }

    @Test fun representativeFullCompanyNamesPunctuationAndSpacingResolve() {
        assertEquals("Nikon", CameraBrands.find(" NIKON CORPORATION ")?.name)
        assertEquals("vivo", CameraBrands.find("VIVO MOBILE COMMUNICATION CO.,LTD.")?.name)
        assertEquals("Canon", CameraBrands.find("  canon  inc. ")?.name)
        assertEquals("Leica", CameraBrands.find("LEICA CAMERA AG")?.name)
        assertEquals("Ricoh", CameraBrands.find("ricoh company,   ltd")?.name)
        assertEquals("Sony", CameraBrands.find("SONY CORP.")?.name)
        assertEquals("Zeiss", CameraBrands.find("carlzeiss")?.name)
        assertEquals("Zeiss", CameraBrands.find("Carl Zeiss AG")?.name)
        assertEquals("Olympus", CameraBrands.find("OLYMPUS IMAGING CORP.")?.name)
        assertEquals("Pentax", CameraBrands.find("Asahi Optical Co.,Ltd")?.name)
        assertEquals("Fujifilm", CameraBrands.find("FUJI PHOTO FILM CO., LTD.")?.name)
        assertEquals("Hasselblad", CameraBrands.find("VICTOR HASSELBLAD")?.name)
        assertEquals("Honor", CameraBrands.find("HONOR Device Co.,Ltd.")?.name)
        assertEquals("Honor", CameraBrands.find("honor device co ltd")?.name)
        assertEquals("Huawei", CameraBrands.find("huawei technologies co., ltd")?.name)
        assertEquals("iQOO", CameraBrands.find("vivo_iQOO")?.name)
        assertEquals("iQOO", CameraBrands.find("  iqoo  mobile  communication   corp. ")?.name)
        assertEquals("Meizu", CameraBrands.find("meizu.inc")?.name)
        assertEquals("OPPO", CameraBrands.find("Guangdong OPPO Mobile Telecommunications Corp.,Ltd.")?.name)
        assertEquals("OnePlus", CameraBrands.find("ONEPLUS Technology (Shenzhen) Co., Ltd.")?.name)
        assertEquals("Xiaomi", CameraBrands.find("xiaomi communications co.,ltd.")?.name)
        assertEquals("Apple", CameraBrands.find("AppleComputer")?.name)
        assertEquals("Apple", CameraBrands.find("apple computer inc")?.name)
        assertEquals("DJI", CameraBrands.find("SZ DJI Technology Co.,Ltd.")?.name)
        assertEquals("Insta360", CameraBrands.find("Shanghai Arashi Vision Co., Ltd.")?.name)
        assertEquals("Insta360", CameraBrands.find("insta 360")?.name)
        assertEquals("Samsung", CameraBrands.find("SAMSUNG ELECTRONICS CO.,LTD.")?.name)
    }

    @Test fun panasonicLumixAliasCollisionIsDeliberatelyResolved() {
        // bare "Panasonic" must map to Panasonic, NOT Lumix
        assertEquals("Panasonic", CameraBrands.find("Panasonic")?.name)
        assertEquals("Panasonic", CameraBrands.find("PANASONIC")?.name)
        assertEquals("Panasonic", CameraBrands.find("Panasonic Corporation")?.name)
        // Lumix only matches its explicit aliases
        assertEquals("Lumix", CameraBrands.find("Lumix")?.name)
        assertEquals("Lumix", CameraBrands.find("LUMIX")?.name)
        assertEquals("Lumix", CameraBrands.find("Panasonic Lumix")?.name)
        assertEquals("Lumix", CameraBrands.find("panasonic   lumix")?.name)
        // and crucially bare Panasonic never collapses into Lumix
        assertNotEquals("Lumix", CameraBrands.find("Panasonic")?.name)
    }

    @Test fun everyAliasNormalizesToAUniqueKeyWithNoCrossBrandCollision() {
        val seen = HashMap<String, String>()
        for (p in CameraBrands.profiles) {
            for (a in p.aliases) {
                val key = CameraBrands.normalize(a) ?: continue
                val previous = seen.put(key, p.name)
                check(previous == null || previous == p.name) {
                    "alias key '$key' collided between '$previous' and '${p.name}' (alias '$a')"
                }
            }
        }
        // every profile contributes at least its own canonical name as a resolvable key
        assertTrue(seen.size >= 24)
    }

    @Test fun allAspectsArePositiveAndFinite() {
        for (p in CameraBrands.profiles) {
            assertTrue("aspect not finite for ${p.name}", p.aspect.isFinite())
            assertTrue("aspect must be > 0 for ${p.name}", p.aspect > 0f)
        }
    }

    @Test fun unknownMakeFallsBackAndIsNotGuessed() {
        assertNull(CameraBrands.find("Panasonic Lumix Corp Future"))
        assertNull(CameraBrands.find("Acme Camera Works"))
        assertEquals("Acme Camera Works", CameraBrands.displayMake("  Acme   Camera Works "))
    }
}
