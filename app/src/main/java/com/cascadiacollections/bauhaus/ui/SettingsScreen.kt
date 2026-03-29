package com.cascadiacollections.bauhaus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import java.time.LocalDate
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.WallpaperTarget

/**
 * Semantic test tags for nodes in [SettingsScreen].
 *
 * Keeping tags here (in main source) lets both the production composable and
 * the instrumented tests reference the same constants without duplicating
 * strings, and without a test-only dependency on the test sources.
 */
object SettingsScreenTestTags {
    const val ARTWORK_PREVIEW = "artwork_preview"
    const val DAILY_UPDATES_SWITCH = "daily_updates_switch"
    const val SET_NOW_BUTTON = "set_now_button"
    const val DOWNLOAD_ICON = "download_icon"
}

/**
 * Stateless settings screen — accepts [UiState] and event callbacks directly.
 *
 * Keeping state out of this composable makes it straightforward to test: callers
 * (and tests) supply a fixed [UiState] snapshot and capture callbacks to verify
 * interactions without standing up a real [BauhausViewModel].
 *
 * ## Layout
 *
 * 1. **Preview card** — today's artwork loaded via Coil from the CDN. Uses the
 *    app-wide [ImageLoader][coil3.ImageLoader] (configured in [BauhausApplication][com.cascadiacollections.bauhaus.BauhausApplication])
 *    which negotiates AVIF > WebP > JPEG and caches via the shared OkHttp client.
 * 2. **Metadata** — title and artist from `/api/today.json` (optional; gracefully
 *    hidden if the CDN is unreachable).
 * 3. **Wallpaper target** — Material 3 segmented button row (Home / Lock / Both).
 * 4. **Daily updates toggle** — enables or disables the [WorkManager][androidx.work.WorkManager] periodic job.
 * 5. **"Set Now" button** — immediate wallpaper apply with loading state.
 *
 * Pull-to-refresh triggers [onRefresh]. Repeated calls within the cooldown window
 * defined in [BauhausViewModel] are silently dropped to guard the upstream service.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    uiState: UiState,
    onWallpaperTargetChange: (WallpaperTarget) -> Unit,
    onSchedulingToggle: (Boolean) -> Unit,
    onSetWallpaperNow: () -> Unit,
    onSaveImage: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // -- Artwork preview --
            val view = LocalView.current
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onSaveImage()
                        },
                    ),
            ) {
                val cacheKey = "${LocalDate.now()}-${uiState.imageRevision}"
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data("https://bauhaus.cascadiacollections.workers.dev/api/today")
                        .memoryCacheKey(cacheKey)
                        .diskCacheKey(cacheKey)
                        .build(),
                    contentDescription = stringResource(R.string.todays_artwork),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .semantics { testTag = SettingsScreenTestTags.ARTWORK_PREVIEW },
                )
            }

            // -- Metadata (title + artist) --
            uiState.metadata?.let { metadata ->
                Column {
                    Text(
                        text = metadata.title.ifEmpty { stringResource(R.string.daily_bauhaus) },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (metadata.artist.isNotEmpty()) {
                        Text(
                            text = metadata.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // -- Wallpaper target selector --
            Text(
                text = stringResource(R.string.wallpaper_target),
                style = MaterialTheme.typography.labelLarge,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                WallpaperTarget.entries.forEachIndexed { index, target ->
                    SegmentedButton(
                        selected = uiState.wallpaperTarget == target,
                        onClick = { onWallpaperTargetChange(target) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = WallpaperTarget.entries.size,
                        ),
                    ) {
                        Text(target.label)
                    }
                }
            }

            // -- Daily updates toggle --
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.daily_updates),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = uiState.schedulingEnabled,
                    onCheckedChange = onSchedulingToggle,
                    modifier = Modifier.semantics { testTag = SettingsScreenTestTags.DAILY_UPDATES_SWITCH },
                )
            }

            uiState.lastUpdated?.let { date ->
                Text(
                    text = stringResource(R.string.last_updated, date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // -- Set wallpaper now --
            Button(
                onClick = onSetWallpaperNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = SettingsScreenTestTags.SET_NOW_BUTTON },
                enabled = !uiState.isSettingWallpaper,
            ) {
                if (uiState.isSettingWallpaper) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.set_now))
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Convenience overload that wires a [BauhausViewModel] into the stateless
 * [SettingsScreen]. Used by [com.cascadiacollections.bauhaus.MainActivity].
 */
@Composable
fun SettingsScreen(
    viewModel: BauhausViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    SettingsScreen(
        uiState = uiState,
        onWallpaperTargetChange = viewModel::setWallpaperTarget,
        onSchedulingToggle = viewModel::setSchedulingEnabled,
        onSetWallpaperNow = viewModel::setWallpaperNow,
        onSaveImage = viewModel::saveImageToGallery,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    )
}
