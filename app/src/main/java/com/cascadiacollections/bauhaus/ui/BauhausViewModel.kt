package com.cascadiacollections.bauhaus.ui

import android.app.Application
import android.app.WallpaperManager
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cascadiacollections.bauhaus.AppContainerProvider
import com.cascadiacollections.bauhaus.CrashReporter
import com.cascadiacollections.bauhaus.WallpaperScheduler
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.BauhausHttpException
import com.cascadiacollections.bauhaus.data.SettingsStore
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import com.cascadiacollections.bauhaus.data.isConnectivityFailure
import com.cascadiacollections.bauhaus.data.serviceToday
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.LinkedHashMap

/** Aspect ratio the preview card uses until the service tells us the artwork's real shape. */
const val FALLBACK_ASPECT_RATIO = 4f / 3f

/** One-shot event for [SnackbarHost][androidx.compose.material3.SnackbarHost] display. */
data class SnackbarEvent(val message: String, val uri: Uri? = null)
data class ShareArtworkEvent(val uri: Uri, val text: String)

/**
 * Immutable snapshot of the settings screen.
 *
 * Every field drives a corresponding UI element in [SettingsScreen]; Compose
 * recomposes only the affected subtree when a single field changes.
 *
 * @property latestDate The newest date the service has published. The UI needs
 *   this — not the device clock — to know when a page should be requested as
 *   `/api/today` rather than `/api/<date>`.
 * @property previewAspectRatio Shape of the preview card, taken from the first
 *   metadata that carries variant dimensions and then held for the session. Each
 *   day's artwork has its own dimensions, so recomputing this per page would
 *   resize the card under the user's finger as they swipe.
 */
data class UiState(
    val wallpaperTarget: WallpaperTarget = WallpaperTarget.BOTH,
    val schedulingEnabled: Boolean = true,
    val lastUpdated: String? = null,
    val latestDate: LocalDate = serviceToday(),
    val previewAspectRatio: Float = FALLBACK_ASPECT_RATIO,
    val visibleDate: LocalDate = serviceToday(),
    val availableDates: List<LocalDate> = listOf(serviceToday()),
    val reachedArchiveStart: Boolean = false,
    val metadata: ArtworkMetadata? = null,
    val isMetadataLoading: Boolean = true,
    val metadataLoadFailed: Boolean = false,
    val isSettingWallpaper: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSavingImage: Boolean = false,
    val imageRevision: Int = 0,
    val isFavorite: Boolean = false,
    val showFavoritesOnly: Boolean = false,
    val favoriteDates: Set<LocalDate> = emptySet(),
)

/**
 * Drives the [SettingsScreen] UI by combining [SettingsRepository] flows with
 * transient action state (loading spinners, snackbar events).
 *
 * ## Dependency injection
 *
 * [settings] and [api] are constructor parameters so the ViewModel can be
 * tested with fakes. Production construction goes through [Factory], which
 * reads the [Application] from [CreationExtras][androidx.lifecycle.viewmodel.CreationExtras].
 *
 * ## Which day is "today"
 *
 * The service publishes by UTC date at 04:00 UTC, so neither the device's local
 * date nor its UTC date reliably names the newest published artwork. Browsing is
 * anchored to [anchorDate], seeded from [serviceToday] and then corrected to
 * [ArtworkMetadata.publishedDate] as soon as `/api/today.json` answers — the
 * service telling us which day it just served. `/api/health` is consulted only
 * when a metadata fetch has already failed, to distinguish "the service has not
 * published that day" from "this device is offline".
 *
 * ## COGs Note
 *
 * Metadata is fetched once per ViewModel lifecycle (i.e. once per activity
 * creation). The service caches `/api/today.json` for 5 min and serves an `ETag`,
 * and the OkHttp disk cache respects both, so rapid config-change rotations cost
 * nothing.
 *
 * The "Set Now" action fetches the image bytes through the same cached
 * [OkHttpClient][okhttp3.OkHttpClient], so if the Coil preview already loaded
 * the image it may already be in the HTTP cache.
 */
class BauhausViewModel(
    application: Application,
    private val settings: SettingsStore,
    private val api: BauhausApiClient,
    private val scheduler: WallpaperScheduler,
) : AndroidViewModel(application) {
    private val maxJumpExpansionDays: Long = 730

    /**
     * Newest date the service is known to have published.
     *
     * Seeded from the UTC clock and replaced by the service's own answer on the
     * first successful metadata load. Read this instead of calling
     * `LocalDate.now()`, which names the wrong day for part of every day.
     */
    private var anchorDate: LocalDate = serviceToday()

    private val archiveMutex = Mutex()
    private val metadataByDate = object : LinkedHashMap<LocalDate, ArtworkMetadata>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, ArtworkMetadata>): Boolean {
            return size > MAX_METADATA_CACHE_SIZE
        }
    }
    private var isAppendingOlderDate: Boolean = false

    /**
     * Tracks the full chronological list of dates available for browsing
     * (independent of the favorites filter). Restored when the user exits
     * favorites-only mode.
     */
    private var allBrowsableDates: List<LocalDate> = listOf(anchorDate)

    /** Minimum milliseconds between user-initiated refreshes (DOS guard). */
    private val refreshCooldownMs: Long = 30_000L
    private var lastRefreshAt: Long = 0L

    private fun getString(@StringRes resId: Int): String =
        getApplication<Application>().getString(resId)

    private val _uiState = MutableStateFlow(
        UiState(
            latestDate = anchorDate,
            visibleDate = anchorDate,
            availableDates = listOf(anchorDate),
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<SnackbarEvent> = _snackbarEvent.asSharedFlow()
    private val _shareArtworkEvent = MutableSharedFlow<ShareArtworkEvent>(extraBufferCapacity = 1)
    val shareArtworkEvent: SharedFlow<ShareArtworkEvent> = _shareArtworkEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            settings.wallpaperTarget.collect { target ->
                _uiState.update { it.copy(wallpaperTarget = target) }
            }
        }
        viewModelScope.launch {
            settings.schedulingEnabled.collect { enabled ->
                _uiState.update { it.copy(schedulingEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.lastUpdated.collect { date ->
                _uiState.update { it.copy(lastUpdated = date) }
            }
        }
        viewModelScope.launch {
            settings.favorites.collect { favStrings ->
                val favDates = favStrings.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
                var metadataDateToLoad: LocalDate? = null
                _uiState.update { state ->
                    val newAvailableDates = if (state.showFavoritesOnly) {
                        favDates.sortedDescending()
                    } else {
                        state.availableDates
                    }
                    val newVisibleDate = if (state.showFavoritesOnly && state.visibleDate !in newAvailableDates) {
                        newAvailableDates.firstOrNull() ?: state.visibleDate
                    } else {
                        state.visibleDate
                    }
                    val cached = metadataByDate[newVisibleDate]
                    if (cached == null) {
                        metadataDateToLoad = newVisibleDate
                    }
                    state.copy(
                        visibleDate = newVisibleDate,
                        metadata = cached,
                        isFavorite = newVisibleDate in favDates,
                        favoriteDates = favDates,
                        availableDates = newAvailableDates,
                    )
                }
                metadataDateToLoad?.let { loadMetadataForDate(it, force = false) }
            }
        }
        viewModelScope.launch {
            try {
                val metadata = api.fetchTodayMetadata()
                metadata.publishedDate?.let { rebaseToLatest(it) }
                metadataByDate[anchorDate] = metadata
                _uiState.update { state ->
                    val next = if (state.visibleDate == anchorDate) {
                        state.copy(metadata = metadata, isMetadataLoading = false, metadataLoadFailed = false)
                    } else {
                        state.copy(isMetadataLoading = false, metadataLoadFailed = false)
                    }
                    next.withPreviewRatioFrom(metadata)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(metadata = null, isMetadataLoading = false, metadataLoadFailed = true) }
                reportMetadataFailure(e)
            }
        }
    }

    /**
     * Adopts the artwork's own dimensions as the preview card's shape, once.
     *
     * The first metadata to carry a usable `variants` entry wins and the ratio is
     * then fixed for the session — see [UiState.previewAspectRatio].
     */
    private fun UiState.withPreviewRatioFrom(metadata: ArtworkMetadata): UiState {
        if (previewAspectRatio != FALLBACK_ASPECT_RATIO) return this
        val ratio = resolvePreviewAspectRatio(metadata)
        return if (ratio == FALLBACK_ASPECT_RATIO) this else copy(previewAspectRatio = ratio)
    }

    /**
     * Adopts [latest] as the newest published date.
     *
     * When the user has not browsed away from the initial page yet, the whole
     * pager is re-seeded so the single visible page carries the service's date
     * rather than the clock's guess. Once they have paged or jumped, only
     * [UiState.latestDate] moves — silently yanking the visible page out from
     * under them would be worse than a one-day-off label.
     */
    private fun rebaseToLatest(latest: LocalDate) {
        if (latest == anchorDate) return
        val previous = anchorDate
        anchorDate = latest

        val untouched = allBrowsableDates == listOf(previous)
        if (untouched) {
            allBrowsableDates = listOf(latest)
        }
        _uiState.update { state ->
            if (untouched && state.availableDates == listOf(previous)) {
                state.copy(
                    latestDate = latest,
                    availableDates = listOf(latest),
                    visibleDate = latest,
                    isFavorite = latest in state.favoriteDates,
                )
            } else {
                state.copy(latestDate = latest)
            }
        }
    }

    fun onArchivePageSelected(pageIndex: Int) {
        val snapshot = _uiState.value
        val selectedDate = snapshot.availableDates.getOrNull(pageIndex) ?: return

        if (snapshot.visibleDate != selectedDate) {
            selectDate(selectedDate)
        }

        if (!snapshot.showFavoritesOnly && !snapshot.reachedArchiveStart && pageIndex == snapshot.availableDates.lastIndex) {
            appendNextOlderDate()
        }
    }

    /**
     * Moves the visible page to [date], showing cached metadata immediately and
     * loading it otherwise.
     */
    private fun selectDate(date: LocalDate) {
        val cached = metadataByDate[date]
        _uiState.update {
            it.copy(
                visibleDate = date,
                metadata = cached,
                isMetadataLoading = cached == null,
                metadataLoadFailed = false,
                isFavorite = date in it.favoriteDates,
            )
        }
        if (cached == null) {
            loadMetadataForDate(date, force = false)
        }
    }

    /**
     * Jumps the pager to [date], extending the archive backwards if needed.
     *
     * Extending costs exactly one request — a body-less
     * [hasArtworkForDate][BauhausApiClient.hasArtworkForDate] probe of the target
     * day. Publishing is daily and write-once, so a day that exists implies every
     * later day exists, and the intervening pages can be appended without being
     * probed individually. Their metadata loads lazily as the user reaches them.
     *
     * The previous implementation fetched full metadata for every day between the
     * target and the oldest loaded page before it could decide, which meant a
     * two-year jump issued over seven hundred requests to answer one question.
     */
    fun jumpToDate(date: LocalDate) {
        if (date.isAfter(anchorDate)) return
        if (date == _uiState.value.visibleDate) return

        val oldestLoaded = allBrowsableDates.lastOrNull() ?: anchorDate
        if (date in allBrowsableDates || !date.isBefore(oldestLoaded)) {
            selectDate(date)
            return
        }

        if (ChronoUnit.DAYS.between(date, oldestLoaded) > maxJumpExpansionDays) {
            _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_archive_jump_too_far)))
            return
        }

        viewModelScope.launch {
            archiveMutex.withLock {
                val exists = try {
                    api.hasArtworkForDate(date)
                } catch (e: Exception) {
                    emitError(e, R.string.error_refresh)
                    return@withLock
                }

                if (!exists) {
                    _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_no_artwork_for_date)))
                    return@withLock
                }

                val appended = buildList {
                    var cursor = oldestLoaded.minusDays(1)
                    while (!cursor.isBefore(date)) {
                        add(cursor)
                        cursor = cursor.minusDays(1)
                    }
                }
                allBrowsableDates = allBrowsableDates + appended

                val cached = metadataByDate[date]
                _uiState.update {
                    it.copy(
                        availableDates = if (it.showFavoritesOnly) it.availableDates else allBrowsableDates,
                        visibleDate = date,
                        metadata = cached,
                        isMetadataLoading = cached == null,
                        metadataLoadFailed = false,
                        isFavorite = date in it.favoriteDates,
                    )
                }
                if (cached == null) {
                    loadMetadataForDate(date, force = false)
                }
            }
        }
    }

    /** Persists the selected wallpaper target (home, lock, or both). */
    fun setWallpaperTarget(target: WallpaperTarget) {
        viewModelScope.launch {
            settings.setWallpaperTarget(target)
        }
    }

    /**
     * Toggles the favorite state of the currently visible artwork date.
     *
     * The change is persisted to DataStore and reflected immediately in [uiState]
     * via the [favorites][SettingsStore.favorites] flow.
     */
    fun toggleFavorite() {
        val date = _uiState.value.visibleDate
        viewModelScope.launch {
            settings.toggleFavorite(date.toString())
        }
    }

    /**
     * Switches between the full chronological browsing mode and a
     * favorites-only view that shows only the dates the user has hearted.
     *
     * When entering favorites-only mode the pager is replaced with the
     * sorted favorites list; exiting restores [allBrowsableDates].
     */
    fun toggleFavoritesFilter() {
        var metadataDateToLoad: LocalDate? = null
        _uiState.update { state ->
            val newShowFavoritesOnly = !state.showFavoritesOnly
            val newAvailableDates = if (newShowFavoritesOnly) {
                state.favoriteDates.sortedDescending()
            } else {
                allBrowsableDates
            }
            val newVisibleDate = if (newAvailableDates.contains(state.visibleDate)) {
                state.visibleDate
            } else {
                newAvailableDates.firstOrNull() ?: state.visibleDate
            }
            val cached = metadataByDate[newVisibleDate]
            if (cached == null) {
                metadataDateToLoad = newVisibleDate
            }
            state.copy(
                showFavoritesOnly = newShowFavoritesOnly,
                availableDates = newAvailableDates,
                visibleDate = newVisibleDate,
                metadata = cached,
                isFavorite = newVisibleDate in state.favoriteDates,
            )
        }
        metadataDateToLoad?.let { loadMetadataForDate(it, force = false) }
    }

    /**
     * Toggles the daily scheduling worker on or off.
     *
     * When disabled, the existing periodic [WorkManager][androidx.work.WorkManager]
     * job is cancelled. Re-enabling re-enqueues it with [ExistingPeriodicWorkPolicy.KEEP][androidx.work.ExistingPeriodicWorkPolicy.KEEP].
     */
    fun setSchedulingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setSchedulingEnabled(enabled)
            if (enabled) scheduler.scheduleDaily() else scheduler.cancelDaily()
        }
    }

    /**
     * Applies the visible artwork as the wallpaper.
     *
     * The bitmap is downsampled to the device screen resolution and recycled
     * after [WallpaperManager.setBitmap] to minimize native memory usage.
     *
     * `lastUpdated` is stamped **only** when the applied artwork is the latest
     * published day, because [WallpaperWorker][com.cascadiacollections.bauhaus.worker.WallpaperWorker]
     * treats that field as "today's artwork is already on screen" and skips its
     * daily fetch when it matches. Stamping it after applying an archive image
     * suppressed the day's real update.
     */
    fun setWallpaperNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSettingWallpaper = true) }
            try {
                val visibleDate = _uiState.value.visibleDate
                val isLatest = visibleDate == anchorDate
                val metrics = getApplication<Application>().resources.displayMetrics
                val bitmap = if (isLatest) {
                    api.fetchTodayImage(
                        maxWidth = metrics.widthPixels,
                        maxHeight = metrics.heightPixels,
                    )
                } else {
                    api.fetchImageForDate(
                        date = visibleDate,
                        maxWidth = metrics.widthPixels,
                        maxHeight = metrics.heightPixels,
                    )
                }
                try {
                    val target = _uiState.value.wallpaperTarget
                    withContext(Dispatchers.IO) {
                        val wallpaperManager = WallpaperManager.getInstance(getApplication())
                        wallpaperManager.setBitmap(bitmap, null, true, target.flag)
                    }
                    if (isLatest) {
                        settings.setLastUpdated(visibleDate.toString())
                    }
                    _uiState.update { it.copy(isSettingWallpaper = false) }
                } finally {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSettingWallpaper = false) }
                emitError(e, R.string.error_set_wallpaper)
            }
        }
    }

    fun shareCurrentArtwork() {
        val snapshot = _uiState.value
        val path = if (snapshot.visibleDate == anchorDate) {
            "/api/today"
        } else {
            BauhausApi.imagePath(snapshot.visibleDate)
        }
        val artworkUri = "${BauhausApi.BASE_URL}$path".toUri()

        val title = snapshot.metadata?.title?.trim().orEmpty()
        val artist = snapshot.metadata?.creator.orEmpty()
        val metadataText = listOfNotNull(
            title.takeIf(String::isNotBlank),
            artist.takeIf(String::isNotBlank),
        ).joinToString(" — ")
            .takeIf(String::isNotBlank)
        val shareText = listOfNotNull(metadataText, artworkUri.toString()).joinToString("\n")
        _shareArtworkEvent.tryEmit(ShareArtworkEvent(uri = artworkUri, text = shareText))
    }

    /**
     * Saves the visible artwork to the device gallery in its original format.
     *
     * Uses [MediaStore] to write into `Pictures/Bauhaus/` without requiring
     * storage permissions (minSdk 35). The `IS_PENDING` flag prevents the
     * media scanner from indexing a partially-written file.
     */
    fun saveImageToGallery() {
        if (_uiState.value.isSavingImage) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingImage = true) }
            val resolver = getApplication<Application>().contentResolver
            var pendingUri: Uri? = null
            var saveSucceeded = false
            try {
                val visibleDate = _uiState.value.visibleDate
                val (bytes, mimeType) = if (visibleDate == anchorDate) {
                    api.fetchTodayImageRaw()
                } else {
                    api.fetchImageRawForDate(visibleDate)
                }
                val extension = when (mimeType) {
                    "image/avif" -> "avif"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val displayName = "bauhaus_${visibleDate}.$extension"

                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Bauhaus")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)) {
                    "MediaStore insert returned null"
                }
                pendingUri = uri

                checkNotNull(resolver.openOutputStream(uri)) {
                    "Failed to open output stream for URI: $uri"
                }.use { it.write(bytes) }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                saveSucceeded = true
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.image_saved), uri))
            } catch (e: Exception) {
                emitError(e, R.string.error_save_image)
            } finally {
                if (!saveSucceeded) {
                    pendingUri?.let { uri ->
                        runCatching { resolver.delete(uri, null, null) }
                            .onFailure { CrashReporter.recordException(it) }
                    }
                }
                _uiState.update { it.copy(isSavingImage = false) }
            }
        }
    }

    /**
     * Refreshes the visible artwork's metadata via a pull-to-refresh gesture.
     *
     * Includes two abuse/DOS guards:
     * 1. **In-flight guard**: drops the call immediately if a refresh is already
     *    in progress, preventing concurrent network requests.
     * 2. **Cooldown guard**: successive calls within [refreshCooldownMs] are
     *    silently dropped to prevent hammering the upstream bauhaus service.
     *    Uses [SystemClock.elapsedRealtime] (monotonic) so the check is immune
     *    to wall-clock adjustments (NTP, manual time changes).
     *
     * The cooldown is only consumed by a refresh that actually reached the
     * service. A failed attempt — most often because the device was offline —
     * used to lock the user out of retrying for the full window, which is exactly
     * when they are most likely to pull again.
     */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        val now = SystemClock.elapsedRealtime()
        if (lastRefreshAt != 0L && now - lastRefreshAt < refreshCooldownMs) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    isMetadataLoading = it.metadata == null,
                    metadataLoadFailed = false,
                )
            }
            try {
                val visibleDate = _uiState.value.visibleDate
                val metadata = if (visibleDate == anchorDate) {
                    api.fetchTodayMetadata()
                } else {
                    api.fetchMetadataForDate(visibleDate)
                }
                lastRefreshAt = SystemClock.elapsedRealtime()
                metadataByDate[visibleDate] = metadata
                _uiState.update { state ->
                    val next = if (state.visibleDate == visibleDate) {
                        state.copy(
                            metadata = metadata,
                            isRefreshing = false,
                            isMetadataLoading = false,
                            metadataLoadFailed = false,
                            imageRevision = state.imageRevision + 1,
                        )
                    } else {
                        state.copy(
                            isRefreshing = false,
                            isMetadataLoading = false,
                            metadataLoadFailed = false,
                        )
                    }
                    next.withPreviewRatioFrom(metadata)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        isMetadataLoading = false,
                        metadataLoadFailed = true,
                    )
                }
                reportMetadataFailure(e)
            }
        }
    }

    private fun loadMetadataForDate(date: LocalDate, force: Boolean) {
        if (!force) {
            metadataByDate[date]?.let { cached ->
                _uiState.update {
                    if (it.visibleDate == date) it.copy(metadata = cached) else it
                }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update {
                if (it.visibleDate == date) {
                    it.copy(isMetadataLoading = true, metadataLoadFailed = false)
                } else {
                    it
                }
            }
            try {
                val metadata = if (date == anchorDate) api.fetchTodayMetadata() else api.fetchMetadataForDate(date)
                metadataByDate[date] = metadata
                _uiState.update { state ->
                    val next = if (state.visibleDate == date) {
                        state.copy(metadata = metadata, isMetadataLoading = false, metadataLoadFailed = false)
                    } else {
                        state
                    }
                    next.withPreviewRatioFrom(metadata)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Surfaced through UiState.metadataLoadFailed; the pager shows an
                // inline failure rather than a snackbar per swiped-past page.
                _uiState.update {
                    if (it.visibleDate == date) {
                        it.copy(metadata = null, isMetadataLoading = false, metadataLoadFailed = true)
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun appendNextOlderDate() {
        if (isAppendingOlderDate || _uiState.value.reachedArchiveStart) return
        val oldest = allBrowsableDates.lastOrNull() ?: anchorDate
        val nextOlderDate = oldest.minusDays(1)
        isAppendingOlderDate = true

        viewModelScope.launch {
            archiveMutex.withLock {
                try {
                    val metadata = api.fetchMetadataForDate(nextOlderDate)
                    metadataByDate[nextOlderDate] = metadata
                    allBrowsableDates = allBrowsableDates + nextOlderDate
                    _uiState.update { state ->
                        if (!state.showFavoritesOnly) {
                            state.copy(availableDates = allBrowsableDates)
                        } else {
                            state
                        }
                    }
                } catch (e: BauhausHttpException) {
                    if (e.code == HTTP_NOT_FOUND) {
                        _uiState.update { it.copy(reachedArchiveStart = true) }
                    } else {
                        emitError(e, R.string.error_refresh)
                    }
                } catch (e: Exception) {
                    emitError(e, R.string.error_refresh)
                } finally {
                    isAppendingOlderDate = false
                }
            }
        }
    }

    /**
     * Maps a failed service call onto a user-facing message.
     *
     * A connectivity failure is a fact about the device, not a defect, so it gets
     * the offline message and is **not** reported to the crash reporter. Note that
     * [com.cascadiacollections.bauhaus.data.BauhausNetworkException] is not an
     * `IOException`, so this must go through
     * [isConnectivityFailure][com.cascadiacollections.bauhaus.data.isConnectivityFailure]
     * — a plain `catch (e: IOException)` misses every wrapped network error and
     * files it as a crash.
     */
    private fun emitError(error: Throwable, @StringRes fallbackRes: Int) {
        if (error is CancellationException) throw error
        if (error.isConnectivityFailure) {
            _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_network)))
            return
        }
        CrashReporter.recordException(error)
        _snackbarEvent.tryEmit(SnackbarEvent(getString(fallbackRes)))
    }

    /**
     * Reports a failed metadata load, asking the service whether it is simply
     * behind on publishing before blaming the network or ourselves.
     *
     * A `404` on the day we believe is current is expected during the window
     * between 00:00 UTC and the 04:00 UTC publish run, and whenever a run has
     * failed. `/api/health` answers which case it is and hands back the newest
     * date it does have, which also lets browsing re-anchor to something real.
     */
    private suspend fun reportMetadataFailure(error: Throwable) {
        if (error is CancellationException) throw error
        if (error.isConnectivityFailure) {
            _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_network)))
            return
        }

        if (error is BauhausHttpException && error.code == HTTP_NOT_FOUND) {
            val health = try {
                api.fetchHealth()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (health != null && !health.isCurrent) {
                health.latestDate?.let { rebaseToLatest(it) }
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_service_stale)))
                return
            }
        }

        CrashReporter.recordException(error)
        _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_refresh)))
    }

    companion object {
        private const val MAX_METADATA_CACHE_SIZE = 256
        private const val HTTP_NOT_FOUND = 404

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
                    "APPLICATION_KEY not found in CreationExtras"
                }
                val containerProvider = app as? AppContainerProvider
                    ?: error("Application must implement AppContainerProvider")
                val container = containerProvider.container
                BauhausViewModel(
                    app,
                    container.settingsRepository,
                    container.bauhausApi,
                    container.wallpaperScheduler,
                )
            }
        }
    }
}
