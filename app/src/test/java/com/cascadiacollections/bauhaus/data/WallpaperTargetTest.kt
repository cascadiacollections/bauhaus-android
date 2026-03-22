package com.cascadiacollections.bauhaus.data

import android.app.WallpaperManager
import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperTargetTest {

    @Test
    fun `HOME flag matches WallpaperManager FLAG_SYSTEM`() {
        assertEquals(WallpaperManager.FLAG_SYSTEM, WallpaperTarget.HOME.flag)
    }

    @Test
    fun `LOCK flag matches WallpaperManager FLAG_LOCK`() {
        assertEquals(WallpaperManager.FLAG_LOCK, WallpaperTarget.LOCK.flag)
    }

    @Test
    fun `BOTH flag is bitwise OR of SYSTEM and LOCK`() {
        assertEquals(
            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
            WallpaperTarget.BOTH.flag,
        )
    }

    @Test
    fun `all entries have non-empty labels`() {
        WallpaperTarget.entries.forEach { target ->
            assert(target.label.isNotEmpty()) { "${target.name} has an empty label" }
        }
    }
}
