package com.cascadiacollections.bauhaus.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.cascadiacollections.bauhaus.AppContainerProvider
import com.cascadiacollections.bauhaus.AppLogger
import com.cascadiacollections.bauhaus.MainActivity
import com.cascadiacollections.bauhaus.R
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Home-screen widget showing the current artwork.
 *
 * ## Cost
 *
 * The widget shares the app's single [OkHttpClient][okhttp3.OkHttpClient] and
 * its disk cache, so a refresh that follows an app launch, a worker run, or a
 * previous widget update is answered locally. `/api/today` carries `max-age=300`
 * and an `ETag`, so even a cold refresh is at worst one conditional request that
 * usually ends in a `304`.
 *
 * Periodic self-refresh is switched **off** in `bauhaus_widget_info.xml`
 * (`updatePeriodMillis="0"`). Nothing about this content changes more than once
 * a day, and the one moment it does change is already known: the worker calls
 * [updateAll] after it sets a new wallpaper. Letting the launcher poll every
 * 30 minutes forever would be a standing cost for no new information.
 *
 * ## Sizing
 *
 * [SizeMode.Exact] plus the decode bounds below mean the bitmap is downsampled
 * to roughly the cells it occupies. A widget bitmap crossing the process
 * boundary has a hard size limit, and a full-resolution wallpaper would blow
 * straight through it.
 */
class BauhausAppWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "BauhausAppWidget"

        /** Density-independent upper bound on the decoded bitmap's longest edge. */
        private const val MAX_EDGE_DP = 480

        /** Refreshes every placed widget. Safe to call when none are placed. */
        suspend fun refresh(context: Context) {
            runCatching { BauhausAppWidget().updateAll(context) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    AppLogger.warn(
                        TAG,
                        AppLogger.Event("widget_refresh_failure"),
                        "Could not refresh widgets: ${e.message}",
                    )
                }
        }
    }

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val api = (context.applicationContext as AppContainerProvider).container.bauhausApi
        val density = context.resources.displayMetrics.density
        val maxEdgePx = (MAX_EDGE_DP * density).roundToInt()

        val bitmap = try {
            api.fetchTodayImage(maxWidth = maxEdgePx, maxHeight = maxEdgePx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A widget that cannot reach the service is not an error worth
            // reporting — it is a phone in a lift. Show the placeholder.
            AppLogger.warn(
                TAG,
                AppLogger.Event("widget_image_failure"),
                "Could not load artwork for widget: ${e.message}",
            )
            null
        }

        provideContent {
            GlanceTheme {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(16.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            provider = ImageProvider(bitmap),
                            contentDescription = context.getString(R.string.todays_artwork),
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp),
                        )
                    } else {
                        Text(
                            text = context.getString(R.string.widget_unavailable),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                            modifier = GlanceModifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}
