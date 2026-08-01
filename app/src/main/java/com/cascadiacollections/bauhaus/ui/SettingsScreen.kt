package com.cascadiacollections.bauhaus.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
    const val DAILY_UPDATES_SWITCH = "daily_updates_switch"
    const val SET_NOW_BUTTON = "set_now_button"
    const val SAVE_IMAGE_BUTTON = "save_image_button"
    const val SHARE_ICON = "share_icon"
    const val DOWNLOAD_ICON = "download_icon"
    const val JUMP_TO_DATE_BUTTON = "jump_to_date_button"
    const val FAVORITE_BUTTON = "favorite_button"
    const val FAVORITES_FILTER_CHIP = "favorites_filter_chip"
    const val VIEW_SOURCE_BUTTON = "view_source_button"
    const val VIEW_LICENSE_BUTTON = "view_license_button"
}

internal data class ArchiveImageRequest(
    val imagePath: String,
    val cacheKey: String,
)

/**
 * Route for [date]'s image.
 *
 * [latestDate] is the newest date the *service* has published, not the device's
 * idea of today — see [UiState.latestDate]. The newest page goes through
 * `/api/today`, which resolves server-side and shares a cache entry with the
 * startup prefetch and the daily worker.
 */
internal fun imagePathForDate(
    date: LocalDate,
    latestDate: LocalDate,
): String = if (date == latestDate) {
    "/api/today"
} else {
    BauhausApi.imagePath(date)
}

internal fun imageCacheKeyForDate(
    date: LocalDate,
    imageRevision: Int,
): String = "${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}-$imageRevision"

internal fun neighborPrefetchRequests(
    dates: List<LocalDate>,
    settledPage: Int,
    latestDate: LocalDate,
    imageRevision: Int,
): List<ArchiveImageRequest> {
    if (dates.isEmpty()) return emptyList()
    val neighbors = listOf(settledPage - 1, settledPage + 1)
        .filter { it in dates.indices }
        .map { dates[it] }
        .distinct()
    return neighbors.map { date ->
        ArchiveImageRequest(
            imagePath = imagePathForDate(date, latestDate),
            cacheKey = imageCacheKeyForDate(date, imageRevision),
        )
    }
}

internal fun previewImageSizePx(size: IntSize): IntSize {
    val maxWidth = 1600
    val maxHeight = 1600
    return IntSize(
        width = size.width.coerceAtLeast(1).coerceAtMost(maxWidth),
        height = size.height.coerceAtLeast(1).coerceAtMost(maxHeight),
    )
}

/**
 * Aspect ratio to lay the preview card out at.
 *
 * The service publishes the stylized artwork's pixel dimensions in the metadata
 * `variants` array, so the real ratio is known without a decode or a second
 * request — the preview can frame the whole artwork instead of cropping it into a
 * guessed 4:3 box.
 *
 * Ratios outside a plausible range are ignored, so a malformed variant entry
 * cannot produce a sliver-thin or screen-swallowing card.
 */
internal fun resolvePreviewAspectRatio(metadata: ArtworkMetadata?): Float {
    val ratio = metadata?.aspectRatio ?: return FALLBACK_ASPECT_RATIO
    return if (ratio in MIN_PREVIEW_ASPECT_RATIO..MAX_PREVIEW_ASPECT_RATIO) ratio else FALLBACK_ASPECT_RATIO
}

internal const val MIN_PREVIEW_ASPECT_RATIO = 0.4f
internal const val MAX_PREVIEW_ASPECT_RATIO = 2.5f

/**
 * Stateless settings screen — accepts [UiState] and event callbacks directly.
 *
 * Keeping state out of this composable makes it straightforward to test: callers
 * (and tests) supply a fixed [UiState] snapshot and capture callbacks to verify
 * interactions without standing up a real [BauhausViewModel].
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: UiState,
    onWallpaperTargetChange: (WallpaperTarget) -> Unit,
    onSchedulingToggle: (Boolean) -> Unit,
    onSetWallpaperNow: () -> Unit,
    onSaveImage: () -> Unit,
    modifier: Modifier = Modifier,
    onJumpToDate: (LocalDate) -> Unit = {},
    onFavoriteToggle: () -> Unit = {},
    onFavoritesFilterToggle: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onArchivePageSelected: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    // Bound the picker by the newest date the service has published rather than
    // by the device clock, which can name a day the archive does not have.
    val newestDate = uiState.latestDate
    val newestUtcMillis = remember(newestDate) { localDateToUtcMillis(newestDate) }
    if (showDatePicker) {
        val datePickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = localDateToUtcMillis(uiState.visibleDate),
            selectableDates = remember(newestUtcMillis, newestDate.year) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= newestUtcMillis
                    override fun isSelectableYear(year: Int): Boolean = year <= newestDate.year
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            onJumpToDate(utcMillisToLocalDate(it))
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

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
            var artworkCardSize by remember { mutableStateOf(IntSize.Zero) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { artworkCardSize = it }
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onSaveImage()
                        },
                        onLongClickLabel = stringResource(R.string.save_image),
                    ),
            ) {
                val visiblePage = uiState.availableDates.indexOf(uiState.visibleDate).coerceAtLeast(0)
                val pagerState = rememberPagerState(
                    initialPage = visiblePage,
                    pageCount = { uiState.availableDates.size },
                )
                val latestDate = uiState.latestDate
                val aspectRatio = uiState.previewAspectRatio
                val context = LocalContext.current
                val imageLoader = remember(context) { SingletonImageLoader.get(context) }
                val artworkPreviewSize = remember(artworkCardSize) { previewImageSizePx(artworkCardSize) }
                val prefetchedNeighborKeys = remember(uiState.imageRevision) { mutableSetOf<String>() }
                LaunchedEffect(pagerState, uiState.availableDates, uiState.imageRevision, latestDate) {
                    snapshotFlow { pagerState.settledPage }
                        .distinctUntilChanged()
                        .collect { pageIndex ->
                            onArchivePageSelected(pageIndex)
                            neighborPrefetchRequests(
                                dates = uiState.availableDates,
                                settledPage = pageIndex,
                                latestDate = latestDate,
                                imageRevision = uiState.imageRevision,
                            ).forEach { request ->
                                if (prefetchedNeighborKeys.add(request.cacheKey)) {
                                    imageLoader.enqueue(
                                        ImageRequest.Builder(context)
                                            .data("${BauhausApi.BASE_URL}${request.imagePath}")
                                            .size(artworkPreviewSize.width, artworkPreviewSize.height)
                                            .allowHardware(false)
                                            .memoryCacheKey(request.cacheKey)
                                            .diskCacheKey(request.cacheKey)
                                            .build(),
                                    )
                                }
                            }
                        }
                }
                LaunchedEffect(visiblePage, uiState.availableDates.size) {
                    if (pagerState.currentPage != visiblePage && visiblePage < pagerState.pageCount) {
                        pagerState.scrollToPage(visiblePage)
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .semantics { testTag = SettingsScreenTestTags.ARTWORK_PAGER },
                ) { page ->
                    val date = uiState.availableDates[page]
                    val cacheKey = imageCacheKeyForDate(date, uiState.imageRevision)
                    val imagePath = imagePathForDate(date, latestDate)
                    val contentDescription = if (date == latestDate) {
                        stringResource(R.string.todays_artwork)
                    } else {
                        stringResource(R.string.artwork_for_date, date.toString())
                    }
                    val imageRequest = remember(context, artworkPreviewSize, cacheKey, imagePath) {
                        ImageRequest.Builder(context)
                            .data("${BauhausApi.BASE_URL}$imagePath")
                            .size(artworkPreviewSize.width, artworkPreviewSize.height)
                            .allowHardware(false)
                            .memoryCacheKey(cacheKey)
                            .diskCacheKey(cacheKey)
                            .build()
                    }
                    // Fit, not Crop: the frame is already the artwork's own ratio,
                    // and days whose dimensions differ should letterbox rather than
                    // lose their edges — this is the preview someone decides from.
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = contentDescription,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspectRatio)
                            .semantics {
                                if (date == uiState.visibleDate) {
                                    testTag = SettingsScreenTestTags.ARTWORK_PREVIEW
                                }
                            },
                    )
                }

                Text(
                    text = stringResource(R.string.viewing_date, uiState.visibleDate.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            Button(
                onClick = { showDatePicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = SettingsScreenTestTags.JUMP_TO_DATE_BUTTON },
            ) {
                Text(stringResource(R.string.jump_to_date))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = uiState.showFavoritesOnly,
                    onClick = onFavoritesFilterToggle,
                    enabled = uiState.showFavoritesOnly || uiState.favoriteDates.isNotEmpty(),
                    label = { Text(stringResource(R.string.favorites_only)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.semantics { testTag = SettingsScreenTestTags.FAVORITES_FILTER_CHIP },
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onFavoriteToggle,
                    modifier = Modifier.semantics { testTag = SettingsScreenTestTags.FAVORITE_BUTTON },
                ) {
                    Icon(
                        imageVector = if (uiState.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(
                            if (uiState.isFavorite) R.string.unfavorite_artwork else R.string.favorite_artwork,
                        ),
                        tint = if (uiState.isFavorite) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // -- Metadata (title, creator, date, source, style credit, licence) --
            uiState.metadata?.let { metadata ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val title = metadata.title.trim().ifBlank { stringResource(R.string.daily_bauhaus) }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (metadata.creator.isNotBlank()) {
                            Text(
                                text = metadata.creator,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (metadata.date.isNotBlank()) {
                            Text(
                                text = metadata.date,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (metadata.source.isNotBlank()) {
                            Text(
                                text = metadata.source,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (metadata.styleCredit.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.style_credit, metadata.styleCredit),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // The service publishes source_url / license_url so consumers
                        // can credit the upstream collection properly. Both are CC0
                        // museum links on scheduled runs; surfacing them is the whole
                        // point of the pipeline republishing them.
                        val attributionUrl = metadata.attributionUrl
                        val licenseLink = metadata.licenseLink
                        val licenseLabel = metadata.licenseLabel
                        if (attributionUrl.isNotBlank() || licenseLink.isNotBlank()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (attributionUrl.isNotBlank()) {
                                    TextButton(
                                        onClick = { onOpenUrl(attributionUrl) },
                                        modifier = Modifier.semantics {
                                            testTag = SettingsScreenTestTags.VIEW_SOURCE_BUTTON
                                        },
                                    ) {
                                        Text(stringResource(R.string.view_source))
                                    }
                                }
                                if (licenseLink.isNotBlank()) {
                                    TextButton(
                                        onClick = { onOpenUrl(licenseLink) },
                                        modifier = Modifier.semantics {
                                            testTag = SettingsScreenTestTags.VIEW_LICENSE_BUTTON
                                        },
                                    ) {
                                        Text(licenseLabel.ifBlank { stringResource(R.string.view_license) })
                                    }
                                }
                            }
                        } else if (licenseLabel.isNotBlank()) {
                            Text(
                                text = licenseLabel,
                                style = MaterialTheme.typography.labelMedium,
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

            Button(
                onClick = onSaveImage,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = SettingsScreenTestTags.SAVE_IMAGE_BUTTON },
                enabled = !uiState.isSavingImage,
            ) {
                if (uiState.isSavingImage) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.save_image))
            }
        }
    }
}


private fun localDateToUtcMillis(date: LocalDate): Long = date
    .atStartOfDay(ZoneOffset.UTC)
    .toInstant()
    .toEpochMilli()

private fun utcMillisToLocalDate(millis: Long): LocalDate = Instant
    .ofEpochMilli(millis)
    .atZone(ZoneOffset.UTC)
    .toLocalDate()
