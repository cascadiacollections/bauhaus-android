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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cascadiacollections.bauhaus.AppContainerProvider
import com.cascadiacollections.bauhaus.CrashReporter
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.WallpaperScheduler
import com.cascadiacollections.bauhaus.data.ArchiveIndex
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.BauhausHttpException
import com.cascadiacollections.bauhaus.data.SettingsStore
import com.cascadiacollections.bauhaus.data.SystemWallpaperSetter
import com.cascadiacollections.bauhaus.data.WallpaperSetter
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import com.cascadiacollections.bauhaus.data.isConnectivityFailure
import com.cascadiacollections.bauhaus.data.serviceToday
import com.cascadiacollections.bauhaus.data.wallpaperTargetSize
import com.cascadiacollections.bauhaus.widget.BauhausAppWidget
import com.cascadiacollections.bauhaus.widget.WidgetImageStore
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.LinkedHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Aspect ratio the preview card uses until the service tells us the artwork's real shape. */
const val FALLBACK_ASPECT_RATIO = 4f / 3f

/**
 * How far [UiState.latestDate] can be trusted.
 *
 * It decides how the newest page's image is requested: a date the service has
 * confirmed is requested by its own immutable `/api/<date>` URL, while the
 * clock's guess can only go through `/api/today`, whose contents change daily.
 */
enum class LatestDateStatus {
    /** The clock's guess; the service has not answered yet. */
    RESOLVING,

    /** The service named the day it is serving. */
    CONFIRMED,

    /** The service could not say; still the clock's guess. */
    UNCONFIRMED
}

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
 * @property latestDateStatus Whether [latestDate] is the service's answer or
 *   still the clock's guess.
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
    val latestDateStatus: LatestDateStatus = LatestDateStatus.RESOLVING,
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
    val favoriteDates: Set<LocalDate> = emptySet()
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
    private val savedState: SavedStateHandle,
    private val wallpaperSetter: WallpaperSetter = SystemWallpaperSetter
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

    /** Which older days exist. Guarded by [archiveMutex]. */
    private val archiveIndex = ArchiveIndex(api::fetchArchivePage)

    private val metadataByDate = object : LinkedHashMap<LocalDate, ArtworkMetadata>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, ArtworkMetadata>): Boolean =
            size > MAX_METADATA_CACHE_SIZE
    }

    /**
     * Tracks the full chronological list of dates available for browsing
     * (independent of the favorites filter). Restored when the user exits
     * favorites-only mode.
     */
    private var allBrowsableDates: List<LocalDate> = listOf(anchorDate)
        set(value) {
            field = value
            // Stored compactly: the oldest date, plus the days inside the span the
            // service never published. The pager rebuilds from [anchorDate] down to
            // the oldest, leaving those out. Gaps are rare, so the list is short.
            savedState[KEY_OLDEST_BROWSED_DATE] = value.lastOrNull()?.toString()
            savedState[KEY_SKIPPED_DATES] = value.zipWithNext()
                .flatMap { (newer, older) -> daysStrictlyBetween(older, newer) }
                .map(LocalDate::toEpochDay)
                .toLongArray()
        }

    /** Minimum milliseconds between user-initiated refreshes (DOS guard). */
    private val refreshCooldownMs: Long = 30_000L
    private var lastRefreshAt: Long = 0L

    /** Pull-to-refresh gestures, served one at a time by the collector in [init]. */
    private val refreshRequests = requestChannel()

    /** Pager-reached-the-end signals, served one at a time by the collector in [init]. */
    private val archiveAppendRequests = requestChannel()

    private fun getString(@StringRes resId: Int): String = getApplication<Application>().getString(resId)

    private val _uiState = MutableStateFlow(
        UiState(
            latestDate = anchorDate,
            visibleDate = anchorDate,
            availableDates = listOf(anchorDate)
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<SnackbarEvent> = _snackbarEvent.asSharedFlow()
    private val _shareArtworkEvent = MutableSharedFlow<ShareArtworkEvent>(extraBufferCapacity = 1)
    val shareArtworkEvent: SharedFlow<ShareArtworkEvent> = _shareArtworkEvent.asSharedFlow()

    init {
        restoreBrowsingState()
        viewModelScope.launch {
            // One writer for the two scalars, so no action has to remember to
            // persist. The archive extent is written by allBrowsableDates' setter.
            uiState.collect { state ->
                savedState[KEY_VISIBLE_DATE] = state.visibleDate.toString()
                savedState[KEY_SHOW_FAVORITES_ONLY] = state.showFavoritesOnly
            }
        }
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
                        availableDates = newAvailableDates
                    )
                }
                metadataDateToLoad?.let { loadMetadataForDate(it, force = false) }
            }
        }
        viewModelScope.launch {
            refreshRequests.receiveAsFlow()
                .filter { isPastRefreshCooldown() }
                .collectRequests { handleRefresh() }
        }
        viewModelScope.launch {
            archiveAppendRequests.receiveAsFlow()
                .filter { !_uiState.value.reachedArchiveStart }
                .collectRequests { appendNextOlderDate() }
        }
        viewModelScope.launch {
            try {
                // Keyed by the day the service says it served, not the clock's
                // guess: the guess is the wrong cache key once the service answers.
                val (date, metadata) = fetchLatestMetadata()
                metadataByDate[date] = metadata
                _uiState.update {
                    if (it.visibleDate == date) {
                        it.copy(isMetadataLoading = false, metadataLoadFailed = false)
                            .showingMetadataFor(date, metadata)
                    } else {
                        it.withPreviewRatioFrom(metadata)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    val unconfirmed = it.copy(latestDateStatus = it.latestDateStatus.settled())
                    if (it.visibleDate == anchorDate) {
                        unconfirmed.copy(metadata = null, isMetadataLoading = false, metadataLoadFailed = true)
                    } else {
                        unconfirmed
                    }
                }
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
     * Folds a freshly fetched [metadata] for [date] into the state.
     *
     * Every load path shares two rules, and this is where they live: metadata
     * reaches the screen only if [date] is *still* the visible page, because the
     * user can swipe away while a request is in flight; and the preview card's
     * shape is adopted from whatever artwork loaded, visible or not, since
     * [withPreviewRatioFrom] only takes the first one it is offered.
     *
     * [bumpImageRevision] is for pull-to-refresh, which has to force Coil to
     * re-read a URL whose contents may have changed. Only a refresh of the page
     * the user is looking at should do it.
     *
     * Loading and failure flags stay with the caller: startup and pull-to-refresh
     * own a screen-wide spinner and clear it unconditionally, while a pager load
     * owns only its own page and must not clear a spinner that now belongs to a
     * different one.
     */
    private fun UiState.showingMetadataFor(
        date: LocalDate,
        metadata: ArtworkMetadata,
        bumpImageRevision: Boolean = false
    ): UiState {
        val next = if (visibleDate == date) {
            copy(
                metadata = metadata,
                imageRevision = if (bumpImageRevision) imageRevision + 1 else imageRevision
            )
        } else {
            this
        }
        return next.withPreviewRatioFrom(metadata)
    }

    /**
     * Fetches [date]'s metadata, paired with the day it actually belongs to.
     *
     * Every day the service has confirmed goes through `/api/<date>.json`, which
     * is `immutable` with a one-year TTL because publishing is write-once. Only the
     * newest page, while its date is still the clock's guess, has to go through
     * `/api/today.json` — and that may answer for a different day than asked.
     */
    private suspend fun fetchMetadata(date: LocalDate): Pair<LocalDate, ArtworkMetadata> =
        if (date == anchorDate && _uiState.value.latestDateStatus != LatestDateStatus.CONFIRMED) {
            fetchLatestMetadata()
        } else {
            date to api.fetchMetadataForDate(date)
        }

    /**
     * Fetches `/api/today.json` and adopts the day it names as the newest
     * published date, returning that day with the metadata.
     *
     * The clock's guess yields to the service in either direction — before the
     * day's publish, the service is on yesterday. A date the service has already
     * confirmed only moves forward: `/api/today.json` is edge-cached, so a stale
     * copy can name an older day than one already seen.
     */
    private suspend fun fetchLatestMetadata(): Pair<LocalDate, ArtworkMetadata> {
        val metadata = api.fetchTodayMetadata()
        val published = metadata.publishedDate
        val confirmed = _uiState.value.latestDateStatus == LatestDateStatus.CONFIRMED
        when {
            published == null -> _uiState.update { it.copy(latestDateStatus = it.latestDateStatus.settled()) }
            !confirmed || published.isAfter(anchorDate) -> rebaseToLatest(published)
        }
        return (published ?: anchorDate) to metadata
    }

    /**
     * Rebuilds where the user was browsing before the process was killed.
     *
     * Only four things are persisted — the oldest date paged to, the days in
     * that span the service never published, the visible date, and the favorites
     * filter — so the pager reconstructs from [anchorDate] down to the oldest
     * without storing the list itself.
     *
     * [anchorDate] is deliberately *not* restored. It is a claim about what the
     * service has published, which may have moved on while the app was dead, so
     * it stays seeded from the clock and corrected by the startup fetch. A
     * restored span that no longer makes sense against it is dropped rather than
     * trusted.
     */
    private fun restoreBrowsingState() {
        val restoredOldest = savedState.get<String>(KEY_OLDEST_BROWSED_DATE)?.toLocalDateOrNull()
        val span = restoredOldest?.let { ChronoUnit.DAYS.between(it, anchorDate) }
        if (span != null && span in 0..maxJumpExpansionDays) {
            val skipped = savedState.get<LongArray>(KEY_SKIPPED_DATES)
                ?.mapTo(HashSet()) { LocalDate.ofEpochDay(it) }
                .orEmpty()
            allBrowsableDates = buildList {
                var cursor = anchorDate
                while (!cursor.isBefore(restoredOldest)) {
                    if (cursor == anchorDate || cursor !in skipped) add(cursor)
                    cursor = cursor.minusDays(1)
                }
            }
        }

        val restoredVisible = savedState.get<String>(KEY_VISIBLE_DATE)
            ?.toLocalDateOrNull()
            ?.takeIf { it in allBrowsableDates }
            ?: anchorDate
        val restoredFavoritesOnly = savedState.get<Boolean>(KEY_SHOW_FAVORITES_ONLY) == true

        _uiState.update {
            it.copy(
                availableDates = allBrowsableDates,
                visibleDate = restoredVisible,
                showFavoritesOnly = restoredFavoritesOnly
            )
        }

        // The startup fetch below asks for anchorDate. If the restored page is a
        // different day, nothing would ever populate it — and that fetch clears
        // isMetadataLoading, so the card would settle into "no metadata, not
        // loading, not failed" and render nothing at all.
        if (restoredVisible != anchorDate) {
            loadMetadataForDate(restoredVisible, force = false)
        }
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
        if (latest == anchorDate) {
            _uiState.update { it.copy(latestDateStatus = LatestDateStatus.CONFIRMED) }
            return
        }
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
                    latestDateStatus = LatestDateStatus.CONFIRMED,
                    availableDates = listOf(latest),
                    visibleDate = latest,
                    isFavorite = latest in state.favoriteDates
                )
            } else {
                state.copy(latestDate = latest, latestDateStatus = LatestDateStatus.CONFIRMED)
            }
        }
    }

    fun onArchivePageSelected(pageIndex: Int) {
        val snapshot = _uiState.value
        val selectedDate = snapshot.availableDates.getOrNull(pageIndex) ?: return

        if (snapshot.visibleDate != selectedDate) {
            selectDate(selectedDate)
        }

        if (!snapshot.showFavoritesOnly && !snapshot.reachedArchiveStart &&
            pageIndex == snapshot.availableDates.lastIndex
        ) {
            archiveAppendRequests.trySend(Unit)
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
                isFavorite = date in it.favoriteDates
            )
        }
        if (cached == null) {
            loadMetadataForDate(date, force = false)
        }
    }

    /**
     * Jumps the pager to [date], extending the archive backwards if needed.
     *
     * Extending reads the archive index ([ArchiveIndex]) for the span between the
     * target and the oldest loaded page — one request for any jump within
     * [maxJumpExpansionDays] — and appends only the days that were published.
     * Publishing has gaps, so appending every calendar day in the span would leave
     * pages for days with no artwork that could never load. Metadata for the
     * appended pages loads lazily as the user reaches them.
     *
     * Everything that reads the pager's extent does so inside [archiveMutex]: an
     * append that lands while this is queued moves the oldest loaded day.
     */
    fun jumpToDate(date: LocalDate) {
        if (date.isAfter(anchorDate)) return
        if (date == _uiState.value.visibleDate) return

        viewModelScope.launch {
            archiveMutex.withLock {
                if (date in allBrowsableDates) {
                    selectDate(date)
                    return@withLock
                }

                val oldestLoaded = allBrowsableDates.lastOrNull() ?: anchorDate
                if (!date.isBefore(oldestLoaded)) {
                    // Inside the loaded span but not a page: a day the service
                    // never published.
                    _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_no_artwork_for_date)))
                    return@withLock
                }

                if (ChronoUnit.DAYS.between(date, oldestLoaded) > maxJumpExpansionDays) {
                    _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_archive_jump_too_far)))
                    return@withLock
                }

                val appended = try {
                    archiveIndex.publishedBetween(from = date, until = oldestLoaded)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emitError(e, R.string.error_refresh)
                    return@withLock
                }

                if (date !in appended) {
                    _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_no_artwork_for_date)))
                    return@withLock
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
                        isFavorite = date in it.favoriteDates
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
                isFavorite = newVisibleDate in state.favoriteDates
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
     *
     * The stamp is the day the applied artwork was published for, which is not
     * necessarily the clock's day: before the day's publish, the newest artwork is
     * yesterday's. Stamping the clock's day then suppressed the worker's run after
     * the publish, and the day's artwork was never applied. If the newest page's
     * date is still the clock's guess, `/api/today.json` is asked which day it is.
     */
    fun setWallpaperNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSettingWallpaper = true) }
            try {
                val visibleDate = _uiState.value.visibleDate
                val isLatest = visibleDate == anchorDate
                // null only when the service's metadata names no day at all.
                val artDate: LocalDate? = if (isLatest &&
                    _uiState.value.latestDateStatus != LatestDateStatus.CONFIRMED
                ) {
                    fetchLatestMetadata().second.publishedDate
                } else {
                    visibleDate
                }
                val targetSize = wallpaperTargetSize(getApplication())
                val bitmap = if (artDate != null) {
                    api.fetchImageForDate(
                        date = artDate,
                        maxWidth = targetSize.width,
                        maxHeight = targetSize.height
                    )
                } else {
                    api.fetchTodayImage(
                        maxWidth = targetSize.width,
                        maxHeight = targetSize.height
                    )
                }
                try {
                    val target = _uiState.value.wallpaperTarget
                    wallpaperSetter.set(getApplication(), bitmap, target)
                    if (isLatest) {
                        // With no publish date to go on, stamp nothing: a wrong
                        // stamp suppresses a whole day of updates.
                        artDate?.let { settings.setLastUpdated(it.toString()) }
                        // The widget shows the newest artwork, so only a set of
                        // the newest date can have changed what it displays.
                        // Written from the bitmap already in hand — the widget
                        // never fetches for itself.
                        WidgetImageStore.write(getApplication(), bitmap)
                        BauhausAppWidget.refresh(getApplication())
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
            artist.takeIf(String::isNotBlank)
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
                // The same route the preview used, so what is saved is what is shown.
                val (bytes, mimeType) = if (visibleDate == anchorDate &&
                    _uiState.value.latestDateStatus != LatestDateStatus.CONFIRMED
                ) {
                    api.fetchTodayImageRaw()
                } else {
                    api.fetchImageRawForDate(visibleDate)
                }
                val extension = when (mimeType) {
                    "image/avif" -> "avif"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val displayName = "bauhaus_$visibleDate.$extension"

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
     * Requests a refresh of the visible artwork's metadata (pull-to-refresh).
     *
     * The request is offered to [refreshRequests] rather than executed here, so
     * the two abuse/DOS guards are properties of the pipeline instead of checks
     * every caller has to get right:
     * 1. **In-flight guard**: [requestChannel] is a rendezvous channel, so
     *    [Channel.trySend] hands off only while the collector is parked waiting.
     *    A gesture made while a refresh is still running is dropped, not queued.
     * 2. **Cooldown guard**: [isPastRefreshCooldown] filters out requests inside
     *    the [refreshCooldownMs] window, so the service is not hammered.
     */
    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    /**
     * Whether enough time has passed since the last refresh that *reached the
     * service* to allow another one.
     *
     * Uses [SystemClock.elapsedRealtime] (monotonic) so the check is immune to
     * wall-clock adjustments (NTP, manual time changes).
     *
     * This is deliberately not `throttleFirst`/`sample`: the window is armed by
     * [handleRefresh] only on success. A failed attempt — most often because the
     * device was offline — used to lock the user out for the full window, which
     * is exactly when they are most likely to pull again.
     */
    private fun isPastRefreshCooldown(): Boolean {
        val last = lastRefreshAt
        return last == 0L || SystemClock.elapsedRealtime() - last >= refreshCooldownMs
    }

    /** Serves one refresh request; see [refresh] for the guards that gate it. */
    private suspend fun handleRefresh() {
        _uiState.update {
            it.copy(
                isRefreshing = true,
                isMetadataLoading = it.metadata == null,
                metadataLoadFailed = false
            )
        }
        try {
            val visibleDate = _uiState.value.visibleDate
            // Refreshing the newest page always asks /api/today.json, confirmed or
            // not: finding out that a newer day was published is the point. Its
            // answer is cached under the day it names, which is not necessarily
            // the page that asked.
            val (date, metadata) = if (visibleDate == anchorDate) {
                fetchLatestMetadata()
            } else {
                fetchMetadata(visibleDate)
            }
            lastRefreshAt = SystemClock.elapsedRealtime()
            metadataByDate[date] = metadata
            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    isMetadataLoading = false,
                    metadataLoadFailed = false
                ).showingMetadataFor(date, metadata, bumpImageRevision = true)
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    isMetadataLoading = false,
                    metadataLoadFailed = true
                )
            }
            reportMetadataFailure(e)
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
                val (resolved, metadata) = fetchMetadata(date)
                metadataByDate[resolved] = metadata
                _uiState.update { state ->
                    val shown = state.showingMetadataFor(resolved, metadata)
                    when (state.visibleDate) {
                        resolved -> shown.copy(isMetadataLoading = false, metadataLoadFailed = false)

                        // /api/today.json answered for another day, and the page
                        // that asked is still on screen: it has nothing to show.
                        date -> shown.copy(metadata = null, isMetadataLoading = false, metadataLoadFailed = true)

                        else -> shown
                    }
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

    /**
     * Extends the pager by the next older published day.
     *
     * "Next older" comes from the archive index, not from subtracting a day:
     * publishing has gaps (2026-10-02 has no artwork, nor do eleven days of July
     * 2026), and treating the first missing day as the start of the archive
     * stranded the pager there for good. The index is read a page at a time and
     * kept, so this costs a request only when it reaches past what has been read.
     * [UiState.reachedArchiveStart] is set only when the index has nothing older.
     *
     * Serves one request from [archiveAppendRequests]; because that collection is
     * sequential, appends cannot overlap each other and no "already appending"
     * flag is needed. [archiveMutex] is still required — it serializes this
     * against [jumpToDate], which mutates [allBrowsableDates] from its own
     * coroutine. For the same reason the oldest loaded date is read *inside* the
     * lock: a jump that lands while this request is queued moves it.
     */
    private suspend fun appendNextOlderDate() {
        archiveMutex.withLock {
            val oldest = allBrowsableDates.lastOrNull() ?: anchorDate
            val nextOlderDate = try {
                archiveIndex.newestBefore(oldest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emitError(e, R.string.error_refresh)
                return@withLock
            }
            if (nextOlderDate == null) {
                _uiState.update { it.copy(reachedArchiveStart = true) }
                return@withLock
            }
            allBrowsableDates = allBrowsableDates + nextOlderDate
            _uiState.update { state ->
                if (!state.showFavoritesOnly) {
                    state.copy(availableDates = allBrowsableDates)
                } else {
                    state
                }
            }
        }
    }

    /**
     * Maps a failed service call onto a user-facing message.
     *
     * This is the common tail for every failure the user sees; specialised
     * handlers such as [reportMetadataFailure] deal with the case they know
     * about and then delegate here.
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
     * Everything other than that one case is [emitError]'s job. The health probe
     * is skipped for connectivity failures: it would cost a request that is
     * certain to fail, and the device being offline already explains the error.
     */
    private suspend fun reportMetadataFailure(error: Throwable) {
        if (error is CancellationException) throw error
        if (!error.isConnectivityFailure && handledAsStaleService(error)) return
        emitError(error, R.string.error_refresh)
    }

    /**
     * Handles the case where the service has not published the requested day yet.
     *
     * A `404` on the day we believe is current is expected during the window
     * between 00:00 UTC and the 04:00 UTC publish run, and whenever a run has
     * failed. `/api/health` answers which case it is and hands back the newest
     * date it does have, which also lets browsing re-anchor to something real.
     *
     * Returns `true` when it has explained the failure to the user, and `false`
     * when the caller should fall through to the generic report — including when
     * the probe itself fails, since an unanswered health check is no evidence
     * that the service is behind.
     */
    private suspend fun handledAsStaleService(error: Throwable): Boolean {
        if (error !is BauhausHttpException || error.code != HTTP_NOT_FOUND) return false

        val health = try {
            api.fetchHealth()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (health.isCurrent) return false

        health.latestDate?.let { rebaseToLatest(it) }
        _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_service_stale)))
        return true
    }

    companion object {
        private const val MAX_METADATA_CACHE_SIZE = 256
        private const val HTTP_NOT_FOUND = 404

        private const val KEY_VISIBLE_DATE = "visible_date"
        private const val KEY_OLDEST_BROWSED_DATE = "oldest_browsed_date"
        private const val KEY_SKIPPED_DATES = "skipped_dates"
        private const val KEY_SHOW_FAVORITES_ONLY = "show_favorites_only"

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
                    createSavedStateHandle()
                )
            }
        }
    }
}

/**
 * A request channel that holds at most the one request currently being served.
 *
 * Rendezvous means [Channel.trySend] hands off only while the collector is parked
 * in `receive`; a request raised while one is still in flight is dropped rather
 * than queued. That makes "only one at a time" a property of the channel instead
 * of a boolean flag every code path has to set and clear correctly.
 *
 * The dropping is the point: these are user gestures (a pull, a swipe to the end
 * of the pager) where a second one during the first should cost nothing, not
 * schedule a second request to a service the maintainer pays for.
 *
 * This must not be "simplified" to `MutableSharedFlow(replay = 0,
 * extraBufferCapacity = 0)`. That looks equivalent but is not: with a zero
 * buffer, `emit` is the only way to complete the handoff, so `tryEmit` returns
 * false whenever there is a collector and *every* request is silently dropped.
 * Giving the shared flow a buffer instead would queue the second gesture rather
 * than drop it, which is also wrong.
 *
 * Both producer and collector run on the main dispatcher, so there is no window
 * between the collector finishing one request and re-entering `receive` in which
 * a gesture could be dropped spuriously.
 */
private fun requestChannel(): Channel<Unit> = Channel(Channel.RENDEZVOUS)

/** A failed attempt to resolve the newest date leaves it unconfirmed; any other status stands. */
private fun LatestDateStatus.settled(): LatestDateStatus =
    if (this == LatestDateStatus.RESOLVING) LatestDateStatus.UNCONFIRMED else this

/** The calendar days after [older] and before [newer], newest first. */
private fun daysStrictlyBetween(older: LocalDate, newer: LocalDate): List<LocalDate> = buildList {
    var cursor = newer.minusDays(1)
    while (cursor.isAfter(older)) {
        add(cursor)
        cursor = cursor.minusDays(1)
    }
}

/** Parses an ISO date, or null if the stored value is not one. */
private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

/**
 * Serves requests from a [requestChannel] one at a time, forever.
 *
 * A failure in [serve] must not tear down the collector, or the first error
 * would silently disable the feature for the rest of the ViewModel's life.
 * [CancellationException] needs care in particular: on the JVM
 * `kotlin.coroutines.cancellation.CancellationException` is an alias for
 * `java.util.concurrent.CancellationException`, so a library throwing the latter
 * as an ordinary error would otherwise stop every later request.
 * [ensureActive] rethrows only when the surrounding scope is genuinely cancelled.
 */
private suspend fun Flow<Unit>.collectRequests(serve: suspend () -> Unit) {
    collect {
        try {
            serve()
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }
}
