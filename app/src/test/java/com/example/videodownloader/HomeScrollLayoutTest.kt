package com.example.videodownloader

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class HomeScrollLayoutTest {
    private fun layout(): String {
        val path = Paths.get("src/main/res/layout/activity_main.xml")
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }

    @Test fun oneScrollContainerOwnsHeaderSettingsToolsAndVersion() {
        val xml = layout()
        assertTrue(xml.contains("androidx.core.widget.NestedScrollView"))
        val scrollStart = xml.indexOf("androidx.core.widget.NestedScrollView")
        assertTrue(xml.indexOf("@+id/layoutDensityBar") > scrollStart)
        assertTrue(xml.indexOf("@+id/rvTools") > scrollStart)
        assertTrue(xml.indexOf("@+id/versionUpdatePill") > scrollStart)
    }

    @Test fun toolsRecyclerDoesNotOwnASecondScrollAxis() {
        val xml = layout()
        assertTrue(xml.contains("android:nestedScrollingEnabled=\"false\""))
        assertFalse(xml.contains("android:layout_height=\"0dp\"\n                android:layout_weight=\"1\""))
    }

    @Test fun versionAndUpdateAreOneCompactControlAtListEnd() {
        val xml = layout()
        val tools = xml.indexOf("@+id/rvTools")
        val versionPill = xml.indexOf("@+id/versionUpdatePill")
        assertTrue(versionPill > tools)
        assertTrue(xml.indexOf("@+id/tvVersion", versionPill) > versionPill)
        assertTrue(xml.indexOf("@+id/btnCheckUpdate", versionPill) > versionPill)
    }

    @Test fun densitySettingsAreCompactContentRatherThanStickyToolbar() {
        val xml = layout()
        assertTrue(xml.contains("@drawable/ios_glass_card"))
        assertTrue(xml.contains("@string/home_layout_density_hint"))
        assertTrue(xml.contains("android:layout_marginStart=\"16dp\""))
    }
}
