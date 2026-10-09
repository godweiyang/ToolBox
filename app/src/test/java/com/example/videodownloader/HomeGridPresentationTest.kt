package com.example.videodownloader

import org.junit.Assert.*
import org.junit.Test

class HomeGridPresentationTest {
    @Test fun columnsAreClampedToSupportedRange() {
        assertEquals(2, GridMetrics.normalizeColumns(1))
        assertEquals(2, GridMetrics.normalizeColumns(2))
        assertEquals(5, GridMetrics.normalizeColumns(5))
        assertEquals(5, GridMetrics.normalizeColumns(9))
    }

    @Test fun descriptionsProgressivelyCollapse() {
        assertEquals(2, GridMetrics.presentation(2).descriptionMaxLines)
        assertEquals(1, GridMetrics.presentation(3).descriptionMaxLines)
        assertFalse(GridMetrics.presentation(4).showDescription)
        assertFalse(GridMetrics.presentation(5).showDescription)
    }

    @Test fun iconAndSpacingShrinkMonotonically() {
        val values = (2..5).map(GridMetrics::presentation)
        for (index in 1 until values.size) {
            assertTrue(values[index].iconDp < values[index - 1].iconDp)
            assertTrue(values[index].outerSpacingDp <= values[index - 1].outerSpacingDp)
            assertTrue(values[index].verticalPaddingDp < values[index - 1].verticalPaddingDp)
        }
    }

    @Test fun everyDensityHasFixedCardAndTwoLineTitleSlots() {
        for (columns in 2..5) {
            val value = GridMetrics.presentation(columns)
            assertTrue("columns=$columns", value.cardHeightDp > 0)
            assertTrue("columns=$columns", value.titleSlotDp > 0)
            assertEquals("columns=$columns", 2, value.titleMaxLines)
        }
    }

    @Test fun threeToFiveColumnsKeepIdenticalGeometryForShortAndWrappedTitles() {
        for (columns in 3..5) {
            val value = GridMetrics.presentation(columns)
            val shortTitleCardHeight = value.cardHeightDp
            val wrappedTitleCardHeight = value.cardHeightDp
            val shortTitleSlot = value.titleSlotDp
            val wrappedTitleSlot = value.titleSlotDp
            assertEquals(shortTitleCardHeight, wrappedTitleCardHeight)
            assertEquals(shortTitleSlot, wrappedTitleSlot)
        }
    }

    @Test fun denseModesKeepReadableMultiLineTitles() {
        assertEquals(2, GridMetrics.presentation(4).titleMaxLines)
        assertEquals(2, GridMetrics.presentation(5).titleMaxLines)
        assertTrue(GridMetrics.presentation(5).titleSp >= 11f)
    }

    @Test fun fiveColumnsFitACompactPhoneWithoutZeroWidthCards() {
        val width = 320
        val recyclerPadding = 20
        val spacing = GridMetrics.presentation(5).outerSpacingDp * 2 * 5
        assertTrue((width - recyclerPadding - spacing) / 5 >= 48)
    }

    @Test fun defaultsRemainResponsiveWhenNoPreferenceExists() {
        assertEquals(2, GridMetrics.defaultColumnsForWidth(360))
        assertEquals(3, GridMetrics.defaultColumnsForWidth(600))
        assertEquals(3, GridMetrics.defaultColumnsForWidth(900))
    }
}
