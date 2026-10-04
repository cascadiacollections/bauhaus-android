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
`full` adds Firebase Crashlytics and Analytics and is never released. CI's
"Full flavor compile check" job assembles it only as a compile check, and it is
not a dependency of release. The Google Services plugin refuses to configure
`full` without `app/google-services.json`, which is gitignored; the job writes
a placeholder first. To compile `full` locally, write the same placeholder (see
the "Write placeholder google-services.json" step in
`.github/workflows/build.yml`) and delete it afterwards. The store listing in
`fastlane/metadata/` states the app has no analytics or crash reporting; that
claim is true of `foss` and would be false if `full` were ever shipped.

Flavor-specific source sets: `app/src/foss/` and `app/src/full/` each provide a
`CrashReporter` with the same API — a no-op and a Crashlytics-backed one.

## The UTC-date contract (read before touching dates)

The service keys every artwork by **UTC** date and publishes at 04:00 UTC.

- Never call `LocalDate.now()`. Use `serviceToday()`
  (`data/ServiceCalendar.kt`), which is `LocalDate.now(ZoneOffset.UTC)`.
- `serviceToday()` is only a seed. The authoritative answer is the publish day
  in `/api/today.json`, exposed as `ArtworkMetadata.publishedDate`.
  `BauhausViewModel` anchors browsing to it (`anchorDate` /
  `UiState.latestDate`, with `UiState.latestDateStatus` saying whether the
  service has confirmed it) and re-anchors when the service disagrees.
- The metadata's `date` field is **not** the publish day. For most artworks it is
  the artwork's own date (`"ca. 1750"`). `publishedDate` resolves, in order:
  `published_date` (written by the pipeline since bauhaus#152, missing from older
  archive entries); then `date`, but only when it is an ISO `yyyy-MM-dd`; then
  the UTC calendar day of `generated_at`; then `null`. The pipeline takes the
  archive key and `generated_at` from the same UTC clock, so for every entry they
  name the same day.
- Between 00:00 and ~04:00 UTC the current UTC day is genuinely not published
  yet. That is what `/api/health` is for — it reports `stale`/`unhealthy` with the
  newest date the service does have. It is consulted only after a metadata fetch
  has already failed; it is `no-store`, so it must never go on the startup path.
- The worker's "already set today" guard and the ViewModel's `lastUpdated` stamp
  must use the same calendar, or the daily update silently stops happening.
  Both stamp the day the applied artwork was **published for**, not the day
  they ran. Before the day's publish, `/api/today` is still yesterday's art.
  Stamping the clock's day then made every later run that day skip, including
  the run after publishing. The worker skips without a request when
  `lastUpdated == serviceToday()`. It skips without downloading the image when
  `lastUpdated` already equals the service's newest day.

## HTTP caching invariants

One `OkHttpClient` (`data/HttpModule.kt`) is shared by the API layer, Coil, and
the worker. Two things must hold:

- Image responses carry `Vary: Accept`. Every image request must send the *same*
  `Accept` header, or Coil and `BauhausApi` produce different cache keys and each
  request happens twice. An application interceptor enforces this; it must keep
  excluding non-image routes (`.json`, `.json.sig`).
- `/api/<date>*` is `immutable` with a one-year TTL because publishing is
  write-once. Do not add cache-busting query parameters to date-keyed URLs.

Coil keeps its own disk cache on top of OkHttp's. Coil 3's default
`CacheStrategy` serves a disk-cache hit **forever**, ignoring `Cache-Control`,
and it caches 404s too. So a Coil cache key must only ever name an immutable
URL. The preview loads every confirmed day, the newest included, from
`/api/<date>` under the key `/api/<date>#<revision>`. `/api/today` is loaded only
while the newest date is unconfirmed, with Coil's disk cache disabled
(`archiveImageRequest` in `SettingsScreen.kt`).

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

The worker retries connectivity failures and other transient faults, up to 3
attempts. It returns `Result.failure()` at once for a failure no retry can fix:
a 4xx `BauhausHttpException`, or a `BauhausDecodeException`. Image decode
failures are typed as `BauhausDecodeException` too, not `IllegalStateException`.

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
- Publishing is daily and write-once but **not** contiguous. A failed pipeline
  run leaves a day with no artwork: 2026-10-02 is missing, and so is
  2026-07-20 through 07-30. So "a later date exists" says nothing about an
  earlier one. The pager learns which days exist from the archive index
  (`GET /api/archive?limit=1000&before=<date>`, read by `data/ArchiveIndex.kt`).
  One page covers any jump within the two-year limit. What has been read is
  kept, so paging back costs a request only when it passes what has already
  been read. Never fall back to probing day by day.

## Testing

Unit tests are Robolectric + JUnit4 + MockK + Turbine, under `app/src/test/`.
Instrumented Compose tests under `app/src/androidTest/` are **not run by CI**.

Assertions use AssertK (`assertThat(actual).isEqualTo(expected)`,
`assertFailure { }.isInstanceOf<T>()`). detekt's `ForbiddenImport` rejects
JUnit and kotlin.test asserts.

`BauhausApiClient` fakes live with the tests, not in shared fixtures. Adding a
method to that interface means updating `FakeBauhausApi` in
`ui/BauhausViewModelFakes.kt` (shared by the `BauhausViewModel*Test` classes)
and `FakeApi` in `WallpaperWorkerTest`. `WallpaperScheduler` and the settings
repository are faked in `BauhausViewModelFakes.kt` too.

Avoid unit tests that reach `WallpaperManager.setBitmap`. The Robolectric
shadow's support for combined `FLAG_SYSTEM or FLAG_LOCK` is not something to
rely on. The worker and the ViewModel take a `WallpaperSetter`
(`data/WallpaperSetter.kt`), so tests can pass a recording fake and still
reach the `lastUpdated` stamp that follows.

## Environment

Requires JDK 21 (pinned in `.mise.toml`) and an Android SDK with
`platforms;android-37.0` and `build-tools;37.0.0`. Point Gradle at it via
`ANDROID_HOME` or `local.properties` (`sdk.dir=…`).

There is no way to build this project without a network-reachable Android SDK and
Google Maven. In sandboxes where those hosts are blocked, verification has to
happen in CI.
