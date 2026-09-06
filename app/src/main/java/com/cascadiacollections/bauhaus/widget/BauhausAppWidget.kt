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
import com.cascadiacollections.bauhaus.AppLogger
import com.cascadiacollections.bauhaus.MainActivity
import com.cascadiacollections.bauhaus.R
import kotlin.coroutines.cancellation.CancellationException

/**
 * Home-screen widget showing the current artwork.
 *
 * ## Cost
 *
 * The widget performs **no** network I/O of its own. It draws whatever
 * [WidgetImageStore] holds, which is written by the two paths that already
 * fetched a bitmap for their own reasons — the daily worker and the in-app
 * "Set Now". See [WidgetImageStore] for why it is a reader rather than a
 * fetcher; the short version is that a launcher calls `provideGlance` for
 * reasons unrelated to new content (add, resize, reboot, process recycle), and
 * none of those should reach a service the maintainer pays per request for.
 *
 * Periodic self-refresh is switched **off** in `bauhaus_widget_info.xml`
 * (`updatePeriodMillis="0"`) for the same reason. Nothing here changes more than
 * once a day, and the one moment it does change is already known: the writers
 * call [refresh] straight after storing a new image.
 *
 * The widget shows a placeholder until the first successful wallpaper update
 * fills the store. That is deliberate — see [WidgetImageStore].
 */
class BauhausAppWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "BauhausAppWidget"

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
        val bitmap = WidgetImageStore.read(context)

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
                            text = context.getString(R.string.widget_awaiting_artwork),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                            modifier = GlanceModifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}
