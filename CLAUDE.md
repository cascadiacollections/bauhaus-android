# bauhaus-android

Android client for the [bauhaus artwork service](https://github.com/cascadiacollections/bauhaus).
Single activity, single screen, no navigation graph.

## Build tasks are always flavored

Two product flavors on a `mode` dimension: `foss` and `full`. AGP therefore does
**not** generate `assembleDebug`, `testDebugUnitTest`, or `installDebug`. Use:

```bash
./gradlew assembleFossDebug
./gradlew testFossDebugUnitTest
./gradlew lintFossDebug
./gradlew jacocoTestReport
```

or the `just` recipes (`just build`, `just test`, `just lint`, `just check`).

`foss` is the shipping flavor — it is what CI builds, releases, and uploads.
`full` adds Firebase Crashlytics and Analytics and is **not built by CI at all**,
so it can break without anyone noticing. The store listing in
`fastlane/metadata/` states the app has no analytics or crash reporting; that
claim is true of `foss` and would be false if `full` were ever shipped.

Flavor-specific source sets: `app/src/foss/` and `app/src/full/` each provide a
`CrashReporter` with the same API — a no-op and a Crashlytics-backed one.

## The UTC-date contract (read before touching dates)

The service keys every artwork by **UTC** date and publishes at 04:00 UTC.

- Never call `LocalDate.now()`. Use `serviceToday()`
  (`data/ServiceCalendar.kt`), which is `LocalDate.now(ZoneOffset.UTC)`.
- `serviceToday()` is only a seed. The authoritative answer is the `date` field
  the service returns in `/api/today.json`, exposed as
  `ArtworkMetadata.publishedDate`. `BauhausViewModel` anchors browsing to it
  (`anchorDate` / `UiState.latestDate`) and re-anchors when the service disagrees.
- Between 00:00 and ~04:00 UTC the current UTC day is genuinely not published
  yet. That is what `/api/health` is for — it reports `stale`/`unhealthy` with the
  newest date the service does have. It is consulted only after a metadata fetch
  has already failed; it is `no-store`, so it must never go on the startup path.
- The worker's "already set today" guard and the ViewModel's `lastUpdated` stamp
  must use the same calendar, or the daily update silently stops happening.

## HTTP caching invariants

One `OkHttpClient` (`data/HttpModule.kt`) is shared by the API layer, Coil, and
the worker. Two things must hold:

- Image responses carry `Vary: Accept`. Every image request must send the *same*
  `Accept` header, or Coil and `BauhausApi` produce different cache keys and each
  request happens twice. An application interceptor enforces this; it must keep
  excluding non-image routes (`.json`, `.json.sig`).
- `/api/<date>*` is `immutable` with a one-year TTL because publishing is
  write-once. Do not add cache-busting query parameters to date-keyed URLs.

## Notifications

Only *user-initiated* runs notify. `WallpaperScheduler.requestImmediateUpdate()`
sets `WallpaperWorker.KEY_USER_INITIATED` in the work's input data, and the
worker consults it before posting anything; the daily periodic run leaves it
unset and stays silent. Progress uses plain notifications rather than
`setForeground`, deliberately — WorkManager foreground work on API 34+ would
oblige the app to declare a `dataSync` foreground service type for a few seconds
of work.

## Error classification

`BauhausNetworkException` is **not** an `IOException` — it lives in the sealed
`BauhausDataException` hierarchy. A bare `catch (e: IOException)` misses every
wrapped connectivity failure and routes it to the generic branch, which reports a
non-bug to Crashlytics on every offline fetch.

Use `Throwable.isConnectivityFailure` (`data/BauhausDataException.kt`). In the
ViewModel that is `emitError()` / `reportMetadataFailure()`; add new call sites to
those rather than writing fresh catch ladders.

Also rethrow `CancellationException` before any generic `catch (e: Exception)` in
a coroutine — `java.util.concurrent.CancellationException` extends
`RuntimeException`, so it is caught by default and would otherwise be reported as
a failure.

## Cost discipline

The service is on Cloudflare free tiers and the maintainer pays per request. The
code has deliberate guards worth preserving:

- A 30-second cooldown on pull-to-refresh, consumed only by a refresh that
  reached the service.
- The worker skips entirely when today's wallpaper is already set, and gives up
  after 3 attempts.
- Startup prefetch runs at most once per day.
- The Quick Settings tile, the launcher shortcuts, and the first-run path do not
  fetch anything themselves. All go through
  `WallpaperScheduler.requestImmediateUpdate()`, which enqueues *unique* work
  under `WallpaperWorker.IMMEDIATE_WORK_NAME` with `ExistingWorkPolicy.KEEP`, so
  repeated taps collapse into one run and the worker's own "already set today"
  guard still applies. Any new entry point that wants an immediate update belongs
  there rather than enqueuing its own request.
- The home-screen widget never polls and never fetches. `updatePeriodMillis="0"`
  in `bauhaus_widget_info.xml`, and `provideGlance` only *reads*
  `WidgetImageStore`. A launcher calls `provideGlance` on add, resize, reboot,
  and process recycle — none of which mean new content — so a fetching widget
  would trickle requests forever for a user who never opens the app. The store
  is written by the two paths that already hold a fetched bitmap (the worker and
  `setWallpaperNow()`), which then call `BauhausAppWidget.refresh()`. The cost is
  a placeholder until the first successful update; keep it that way.
- Archive existence is probed with a body-less `HEAD` on `/api/<date>.json`, and a
  date that exists implies every later date exists (publishing is contiguous and
  write-once), so extending the pager costs one request, not one per day.

## Testing

Unit tests are Robolectric + JUnit4 + MockK + Turbine, under `app/src/test/`.
Instrumented Compose tests under `app/src/androidTest/` are **not run by CI**.

`BauhausApiClient` fakes live in the test files themselves — adding a method to
that interface means updating the fakes in `BauhausViewModelTest` and
`WallpaperWorkerTest`. `WallpaperScheduler` is faked the same way in
`BauhausViewModelTest`.

Avoid unit tests that reach `WallpaperManager.setBitmap`; the Robolectric shadow's
support for combined `FLAG_SYSTEM or FLAG_LOCK` is not something to rely on.

## Environment

Requires JDK 21 (pinned in `.mise.toml`) and an Android SDK with
`platforms;android-37.0` and `build-tools;37.0.0`. Point Gradle at it via
`ANDROID_HOME` or `local.properties` (`sdk.dir=…`).

There is no way to build this project without a network-reachable Android SDK and
Google Maven. In sandboxes where those hosts are blocked, verification has to
happen in CI.
