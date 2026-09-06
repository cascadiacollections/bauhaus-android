package com.cascadiacollections.bauhaus.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.cascadiacollections.bauhaus.AppLogger
import com.cascadiacollections.bauhaus.MainActivity
import com.cascadiacollections.bauhaus.R

/**
 * Progress and outcome notifications for user-initiated wallpaper updates.
 *
 * ## Why not a foreground service
 *
 * WorkManager can show progress via `setForeground`, but on API 34+ that obliges
 * the app to declare a `dataSync` foreground service type — a large,
 * policy-relevant permission footprint for a few seconds of work. These are
 * ordinary notifications posted and cancelled by the worker instead, which needs
 * only `POST_NOTIFICATIONS`.
 *
 * ## Live Updates
 *
 * On API 36+ the in-progress notification uses [Notification.ProgressStyle] and
 * asks to be a *promoted ongoing* notification — a Live Update, surfaced on the
 * lock screen and in the status bar chip rather than buried in the shade. The
 * system is free to decline, and API 35 falls back to an ordinary indeterminate
 * progress notification, so nothing depends on the promotion being granted.
 *
 * ## Silence is the default
 *
 * Only *user-initiated* runs notify — a tile tap, a launcher shortcut, or the
 * first-run fetch. The daily scheduled update stays silent: a wallpaper the user
 * did not ask for in that moment is not worth a notification, and this app has
 * no business adding to anyone's notification load once a day forever.
 */
object WallpaperNotifier {

    private const val TAG = "WallpaperNotifier"
    private const val CHANNEL_ID = "wallpaper_updates"

    /** In-progress and outcome notifications replace each other, so they share an id. */
    private const val NOTIFICATION_ID = 1

    /** `Build.VERSION_CODES.BAKLAVA`, which the compile SDK does not name. */
    private const val SDK_LIVE_UPDATES = 36

    /**
     * `Build.VERSION_CODES_FULL.BAKLAVA_1` — Android 16 QPR1, where promoted
     * ongoing notifications arrived. Full version codes are `major * 100_000 +
     * minor`, and the platform does not name this one either.
     */
    private const val SDK_FULL_PROMOTED_ONGOING = 3_600_001

    /**
     * Posts an ongoing, indeterminate "updating" notification.
     *
     * No-ops when the user has not granted `POST_NOTIFICATIONS`. The update must
     * never depend on this: the wallpaper still changes, the user just does not
     * watch it happen.
     */
    fun showUpdating(context: Context) {
        val builder = baseBuilder(context)
            .setContentTitle(context.getString(R.string.notification_updating_title))
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= SDK_LIVE_UPDATES) {
            builder.setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
            // Promotion landed a minor release after ProgressStyle, so it needs
            // its own, finer-grained check against the full version code.
            if (Build.VERSION.SDK_INT_FULL >= SDK_FULL_PROMOTED_ONGOING) {
                builder.setRequestPromotedOngoing(true)
            }
        } else {
            builder.setProgress(0, 0, true)
        }

        notify(context, builder.build())
    }

    /** Clears the in-progress notification after a successful update. */
    fun clear(context: Context) {
        context.getSystemService<NotificationManager>()?.cancel(NOTIFICATION_ID)
    }

    /**
     * Replaces the in-progress notification with a dismissible failure notice.
     *
     * Posted only when the worker gives up for good. A tile tap that quietly
     * achieves nothing is worse than no tile at all, but one notification per
     * retry would be noise.
     */
    fun showFailed(context: Context) {
        val builder = baseBuilder(context)
            .setContentTitle(context.getString(R.string.notification_failed_title))
            .setContentText(context.getString(R.string.notification_failed_text))
            .setAutoCancel(true)
        notify(context, builder.build())
    }

    private fun baseBuilder(context: Context): Notification.Builder {
        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_bauhaus)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
    }

    private fun notify(context: Context, notification: Notification) {
        if (!hasPermission(context)) return
        runCatching {
            context.getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification)
        }.onFailure { e ->
            AppLogger.warn(
                TAG,
                AppLogger.Event("notification_post_failure"),
                "Could not post wallpaper notification: ${e.message}",
            )
        }
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_updates),
            // LOW, not DEFAULT: this reports on work the user just asked for.
            // It should be visible, never audible.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_updates_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
