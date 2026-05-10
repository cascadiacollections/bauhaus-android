package com.cascadiacollections.bauhaus.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Semantic test tags for nodes in [SettingsScreen].
 *
 * Keeping tags here (in main source) lets both the production composable and
 * the instrumented tests reference the same constants without duplicating
 * strings, and without a test-only dependency on the test sources.
 */
object SettingsScreenTestTags {
    const val ARTWORK_PREVIEW = "artwork_preview"
    const val ARTWORK_PAGER = "artwork_pager"
    const val ARTWORK_STATE = "artwork_state"
    const val ARTWORK_RETRY_BUTTON = "artwork_retry_button"
    const val METADATA_STATE = "metadata_state"
    const val METADATA_RETRY_BUTTON = "metadata_retry_button"
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
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    uiState: UiState,
    onWallpaperTargetChange: (WallpaperTarget) -> Unit,
    onSchedulingToggle: (Boolean) -> Unit,
    onSetWallpaperNow: () -> Unit,
    onSaveImage: () -> Unit,
    onArchivePageSelected: (Int) -> Unit,
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
                        onLongClickLabel = stringResource(R.string.save_image),
                    ),
            ) {
                if (uiState.availableDates.isEmpty()) {
                    RetryStateMessage(
                        text = stringResource(R.string.artwork_unavailable),
                        buttonText = stringResource(R.string.retry),
                        onRetry = onRefresh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .semantics { testTag = SettingsScreenTestTags.ARTWORK_STATE },
                        buttonModifier = Modifier.semantics { testTag = SettingsScreenTestTags.ARTWORK_RETRY_BUTTON },
                    )
                } else {
                    val pagerState = rememberPagerState(pageCount = { uiState.availableDates.size })
                    val today = LocalDate.now()
                    LaunchedEffect(pagerState) {
                        snapshotFlow { pagerState.currentPage }
                            .distinctUntilChanged()
                            .collect { onArchivePageSelected(it) }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .semantics { testTag = SettingsScreenTestTags.ARTWORK_PAGER },
                    ) { page ->
                        val date = uiState.availableDates[page]
                        var artworkRetryCount by rememberSaveable(date, uiState.imageRevision) { mutableIntStateOf(0) }
                        val cacheKey = "${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}-${uiState.imageRevision}"
                        val imagePath = if (date == today) "/api/today" else "/api/${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}"
                        val contentDescription = if (date == today) {
                            stringResource(R.string.todays_artwork)
                        } else {
                            stringResource(R.string.artwork_for_date, date.toString())
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                                .semantics {
                                    if (date == uiState.visibleDate) {
                                        testTag = SettingsScreenTestTags.ARTWORK_PREVIEW
                                    }
                                },
                        ) {
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data("${BauhausApi.BASE_URL}$imagePath")
                                    .memoryCacheKey("$cacheKey-$artworkRetryCount")
                                    .diskCacheKey("$cacheKey-$artworkRetryCount")
                                    .build(),
                                contentDescription = contentDescription,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                when (painter.state) {
                                    is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                                    is AsyncImagePainter.State.Loading,
                                    is AsyncImagePainter.State.Empty -> {
                                        StateMessage(
                                            text = stringResource(R.string.artwork_loading),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .semantics { testTag = SettingsScreenTestTags.ARTWORK_STATE },
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    }

                                    is AsyncImagePainter.State.Error -> {
                                        RetryStateMessage(
                                            text = stringResource(R.string.artwork_error),
                                            buttonText = stringResource(R.string.retry),
                                            onRetry = {
                                                artworkRetryCount += 1
                                                onRefresh()
                                            },
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .semantics { testTag = SettingsScreenTestTags.ARTWORK_STATE },
                                            buttonModifier = Modifier.semantics { testTag = SettingsScreenTestTags.ARTWORK_RETRY_BUTTON },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.viewing_date, uiState.visibleDate.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            // -- Metadata (title + artist) --
            val metadata = uiState.metadata
            val metadataUnavailable = metadata == null || (metadata.title.isBlank() && metadata.artist.isBlank())
            when {
                uiState.isMetadataLoading -> {
                    StateMessage(
                        text = stringResource(R.string.metadata_loading),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 72.dp)
                            .semantics { testTag = SettingsScreenTestTags.METADATA_STATE },
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }

                uiState.metadataLoadFailed && metadata == null -> {
                    RetryStateMessage(
                        text = stringResource(R.string.metadata_error),
                        buttonText = stringResource(R.string.retry),
                        onRetry = onRefresh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 72.dp)
                            .semantics { testTag = SettingsScreenTestTags.METADATA_STATE },
                        buttonModifier = Modifier.semantics { testTag = SettingsScreenTestTags.METADATA_RETRY_BUTTON },
                    )
                }

                metadataUnavailable -> {
                    RetryStateMessage(
                        text = stringResource(R.string.metadata_unavailable),
                        buttonText = stringResource(R.string.retry),
                        onRetry = onRefresh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 72.dp)
                            .semantics { testTag = SettingsScreenTestTags.METADATA_STATE },
                        buttonModifier = Modifier.semantics { testTag = SettingsScreenTestTags.METADATA_RETRY_BUTTON },
                    )
                }

                else -> {
                    Column {
                        Text(
                            text = metadata.title.ifEmpty { stringResource(R.string.daily_bauhaus) },
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
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
                        val labelRes = when (target) {
                            WallpaperTarget.HOME -> R.string.wallpaper_target_home
                            WallpaperTarget.LOCK -> R.string.wallpaper_target_lock
                            WallpaperTarget.BOTH -> R.string.wallpaper_target_both
                        }
                        Text(stringResource(labelRes))
                    }
                }
            }

            // -- Daily updates toggle --
            ListItem(
                headlineContent = { Text(stringResource(R.string.daily_updates)) },
                trailingContent = {
                    Switch(
                        checked = uiState.schedulingEnabled,
                        onCheckedChange = null,
                    )
                },
                modifier = Modifier
                    .toggleable(
                        value = uiState.schedulingEnabled,
                        onValueChange = onSchedulingToggle,
                        role = Role.Switch,
                    )
                    .semantics { testTag = SettingsScreenTestTags.DAILY_UPDATES_SWITCH },
            )

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
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.set_now))
            }
        }
    }
}

@Composable
private fun StateMessage(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RetryStateMessage(
    text: String,
    buttonText: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRetry,
                modifier = buttonModifier,
            ) {
                Text(buttonText)
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onWallpaperTargetChange = viewModel::setWallpaperTarget,
        onSchedulingToggle = viewModel::setSchedulingEnabled,
        onSetWallpaperNow = viewModel::setWallpaperNow,
        onSaveImage = viewModel::saveImageToGallery,
        onArchivePageSelected = viewModel::onArchivePageSelected,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    )
}
