package com.cascadiacollections.bauhaus.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Applies a bitmap as the wallpaper on [WallpaperTarget]'s screens.
 *
 * An interface so the worker and the ViewModel can be tested past the point of
 * applying the wallpaper: Robolectric's `WallpaperManager` shadow is not reliable
 * for combined `FLAG_SYSTEM or FLAG_LOCK`, and that point is where `lastUpdated`
 * gets stamped.
 */
fun interface WallpaperSetter {
    suspend fun set(context: Context, bitmap: Bitmap, target: WallpaperTarget)
}

/** The real thing: [WallpaperManager.setBitmap] with backup allowed, off the main thread. */
val SystemWallpaperSetter = WallpaperSetter { context, bitmap, target ->
    withContext(Dispatchers.IO) {
        WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, target.flag)
    }
}
