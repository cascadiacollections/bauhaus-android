package com.cascadiacollections.bauhaus.data

import android.app.WallpaperManager

/**
 * Wallpaper destination screen(s).
 *
 * Maps user-facing labels to [WallpaperManager] flag constants used by
 * [WallpaperManager.setBitmap]. The [flag] property can be passed directly
 * as the `which` parameter.
 *
 * @property flag Bitmask for [WallpaperManager.setBitmap]'s `which` parameter.
 * @property label User-facing display text for the segmented button UI.
 */
enum class WallpaperTarget(val flag: Int, val label: String) {
    /** Home screen only. */
    HOME(WallpaperManager.FLAG_SYSTEM, "Home"),

    /** Lock screen only. */
    LOCK(WallpaperManager.FLAG_LOCK, "Lock"),

    /** Both home and lock screens. */
    BOTH(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK, "Both");
}
