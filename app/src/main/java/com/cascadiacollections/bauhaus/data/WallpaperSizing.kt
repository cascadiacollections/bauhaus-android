package com.cascadiacollections.bauhaus.data

import android.app.WallpaperManager
import android.content.Context
import android.util.Size
import android.view.WindowManager

/**
 * Pixel size to decode a wallpaper at on this device.
 *
 * Prefers [WallpaperManager.getDesiredMinimumWidth] /
 * [WallpaperManager.getDesiredMinimumHeight], which is what the current launcher
 * actually wants: home screens that scroll the wallpaper with parallax ask for
 * something wider than the display, and handing them a screen-width bitmap makes
 * the system upscale it. Both getters return `0` when the launcher expresses no
 * preference, which is the only reason for a fallback.
 *
 * The fallback reads [WindowManager.getMaximumWindowMetrics] rather than
 * `resources.displayMetrics`. Display metrics off a non-UI context are not a
 * dependable display size — and the daily worker has no UI context at all, so
 * what it used to read was not necessarily the screen it was sizing for.
 */
fun wallpaperTargetSize(context: Context): Size {
    val wallpaperManager = WallpaperManager.getInstance(context)
    val desiredWidth = wallpaperManager.desiredMinimumWidth
    val desiredHeight = wallpaperManager.desiredMinimumHeight
    if (desiredWidth > 0 && desiredHeight > 0) {
        return Size(desiredWidth, desiredHeight)
    }

    val bounds = context.getSystemService(WindowManager::class.java)
        ?.maximumWindowMetrics
        ?.bounds
    if (bounds != null && !bounds.isEmpty) {
        return Size(bounds.width(), bounds.height())
    }

    val metrics = context.resources.displayMetrics
    return Size(metrics.widthPixels, metrics.heightPixels)
}
