package com.cascadiacollections.bauhaus.data

import android.app.WallpaperManager
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import org.junit.Test

class WallpaperTargetTest {

    @Test
    fun `HOME flag matches WallpaperManager FLAG_SYSTEM`() {
        assertThat(WallpaperTarget.HOME.flag).isEqualTo(WallpaperManager.FLAG_SYSTEM)
    }

    @Test
    fun `LOCK flag matches WallpaperManager FLAG_LOCK`() {
        assertThat(WallpaperTarget.LOCK.flag).isEqualTo(WallpaperManager.FLAG_LOCK)
    }

    @Test
    fun `BOTH flag is bitwise OR of SYSTEM and LOCK`() {
        assertThat(WallpaperTarget.BOTH.flag).isEqualTo(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
    }

    @Test
    fun `enum still has exactly three targets`() {
        assertThat(WallpaperTarget.entries).hasSize(3)
    }
}
