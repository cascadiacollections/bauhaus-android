package com.cascadiacollections.bauhaus.data

import android.app.WallpaperManager

enum class WallpaperTarget(val flag: Int, val label: String) {
    HOME(WallpaperManager.FLAG_SYSTEM, "Home"),
    LOCK(WallpaperManager.FLAG_LOCK, "Lock"),
    BOTH(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK, "Both");
}
