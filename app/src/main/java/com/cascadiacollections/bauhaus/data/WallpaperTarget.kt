package com.cascadiacollections.bauhaus.data

import android.app.WallpaperManager
import androidx.annotation.StringRes
import com.cascadiacollections.bauhaus.R

/**
 * Wallpaper destination screen(s).
 *
 * Maps user-facing labels to [WallpaperManager] flag constants used by
 * [WallpaperManager.setBitmap]. The [flag] property can be passed directly
 * as the `which` parameter.
 *
 * @property flag Bitmask for [WallpaperManager.setBitmap]'s `which` parameter.
 * @property labelRes String resource ID for the user-facing segmented button label.
 */
enum class WallpaperTarget(val flag: Int, @StringRes val labelRes: Int) {
    /** Home screen only. */
    HOME(WallpaperManager.FLAG_SYSTEM, R.string.wallpaper_target_home),

    /** Lock screen only. */
    LOCK(WallpaperManager.FLAG_LOCK, R.string.wallpaper_target_lock),

    /** Both home and lock screens. */
    BOTH(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK, R.string.wallpaper_target_both);
}
