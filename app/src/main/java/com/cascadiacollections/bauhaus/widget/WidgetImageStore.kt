package com.cascadiacollections.bauhaus.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.cascadiacollections.bauhaus.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The single artwork bitmap the home-screen widget draws.
 *
 * ## Why a store rather than a fetch
 *
 * The widget deliberately performs **no** network I/O. A launcher can call
 * `provideGlance` for reasons that have nothing to do with new content — the
 * widget being added, resized, moved, or restored after a reboot, or the host
 * process being recycled. If each of those went to the service, a user who
 * placed the widget and never opened the app again would still generate a slow
 * trickle of requests forever, and the maintainer pays per request.
 *
 * So the widget is strictly a *reader*. The only writers are the two code paths
 * that already hold a freshly fetched bitmap for their own reasons — the daily
 * worker and the in-app "Set Now" — and neither fetches anything extra to feed
 * it. The widget therefore costs exactly zero additional requests over the life
 * of the install.
 *
 * The cost of that choice is a widget that shows a placeholder until the first
 * successful wallpaper update. That is a real gap, but it is short (the daily
 * worker or a tile tap closes it) and it is the honest trade for never
 * surprising anyone with background traffic.
 *
 * ## Storage
 *
 * One file in `cacheDir`, overwritten in place. It is a cache in the strict
 * sense — losing it costs a placeholder until the next update, never
 * correctness — so it belongs where the system may reclaim it, and it is
 * excluded from backup by `backup_rules.xml` along with the rest of the cache.
 *
 * Stored downsampled to [MAX_EDGE_PX]. A widget bitmap crosses a process
 * boundary into the launcher and there is a hard limit on its size; a
 * full-resolution wallpaper would blow straight through it.
 */
internal object WidgetImageStore {

    private const val TAG = "WidgetImageStore"
    private const val FILE_NAME = "widget_artwork.webp"

    /**
     * Upper bound on the stored bitmap's longest edge.
     *
     * Generous enough for a widget spanning a tablet home screen, small enough
     * to stay well inside the launcher's transfer limit.
     */
    private const val MAX_EDGE_PX = 1024

    private const val QUALITY = 90

    private fun file(context: Context) = File(context.cacheDir, FILE_NAME)

    /**
     * Replaces the stored artwork with [bitmap], downsampled as needed.
     *
     * Best-effort: a failure here means the widget keeps showing the previous
     * artwork, which is never worth failing a wallpaper update over. Does not
     * recycle [bitmap] — the caller owns it.
     */
    suspend fun write(context: Context, bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            try {
                val scaled = bitmap.downsampledForWidget()
                try {
                    // Written to a temporary file and renamed, so a widget
                    // reading concurrently sees either the old image or the new
                    // one, never a half-written file.
                    val target = file(context)
                    val temp = File(target.parentFile, "$FILE_NAME.tmp")
                    temp.outputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
                    }
                    if (!temp.renameTo(target)) {
                        temp.delete()
                        error("could not replace $FILE_NAME")
                    }
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(
                    TAG,
                    AppLogger.Event("widget_image_write_failure"),
                    "Could not store widget artwork: ${e.message}",
                )
            }
        }
    }

    /** The stored artwork, or `null` when nothing has been stored yet. */
    suspend fun read(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        val file = file(context)
        if (!file.exists()) return@withContext null
        try {
            BitmapFactory.decodeFile(file.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(
                TAG,
                AppLogger.Event("widget_image_read_failure"),
                "Could not read widget artwork: ${e.message}",
            )
            null
        }
    }

    /**
     * Returns `this` when already small enough, otherwise a scaled copy.
     *
     * Callers must not assume a new object: compare identity before recycling.
     */
    private fun Bitmap.downsampledForWidget(): Bitmap {
        val longestEdge = max(width, height)
        if (longestEdge <= MAX_EDGE_PX) return this
        val scale = MAX_EDGE_PX.toFloat() / longestEdge
        return scale(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
        )
    }
}
