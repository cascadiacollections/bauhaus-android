# Bauhaus

A daily artwork wallpaper app for Android.

Each morning the [bauhaus service](https://github.com/cascadiacollections/bauhaus)
takes a CC0 landscape from the Metropolitan Museum of Art or the Art Institute of
Chicago, runs AdaIN neural style transfer against a curated reference (Monet,
Hokusai, Cezanne, Turner, …), and publishes it. This app fetches that artwork and
sets it as your wallpaper.

## Features

- Daily artwork from the [bauhaus Cloudflare Workers service](https://bauhaus.cascadiacollections.workers.dev)
- Set as home screen, lock screen, or both
- Browse, favorite, and re-apply any day from the archive
- Save to gallery or share, with the artwork's source and licence linked
- Background updates via WorkManager
- Quick Settings tile for a one-tap update from the notification shade
- Home-screen widget (Glance) showing the current artwork
- Launcher shortcuts for "set today's" and "browse the archive"
- Per-app language support (`localeConfig`)
- Baseline profile for optimized startup

## Tech Stack

- Jetpack Compose with Material 3
- Coil 3 for image loading, sharing one OkHttp 5 client (and its disk cache) with the API layer
- WorkManager for background wallpaper updates
- DataStore Preferences
- kotlinx-serialization

## Requirements

| | |
|---|---|
| Min SDK | 35 (Android 15) |
| compileSdk / targetSdk | 37 |
| JDK | 21 (Temurin — pinned in `.mise.toml`) |
| Android SDK | `platforms;android-37.0`, `build-tools;37.0.0` |

Authoritative versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml);
this table is a summary, so prefer the catalog if they ever disagree.

> **Note:** AGP currently tracks an alpha (`9.4.0-alpha06`). Behaviour can change
> between alphas.

## Build variants

Two product flavors on the `mode` dimension. **Every Gradle task is flavored** —
there is no bare `assembleDebug` or `testDebugUnitTest`.

| Flavor | Contents |
|--------|----------|
| `foss` | No Google or proprietary dependencies. This is what CI builds, releases, and ships. |
| `full` | Adds Firebase Crashlytics and Analytics. Not built by CI. |

Build types: `debug`, `release`, and `benchmark` (a non-debuggable release used by
the macrobenchmark module).

## Build

```bash
# Debug APK
./gradlew assembleFossDebug

# Unit tests
./gradlew testFossDebugUnitTest

# Lint (warnings are errors)
./gradlew lintFossDebug

# Coverage report -> app/build/reports/jacoco/jacocoTestReport/
./gradlew jacocoTestReport

# Install on a connected device
./gradlew installFossDebug
```

Or with [Just](https://github.com/casey/just):

```bash
just build          # assemble
just test           # unit tests
just lint           # lint
just coverage       # jacoco report
just check          # everything CI runs, in one go
just install        # install on a connected device
just build-full     # the Firebase-carrying flavor
just deps           # refresh gradle/libs.versions.toml
just                # list all recipes
```

Recipes default to the `foss` flavor; override with `just flavor=full build`.

## Development environment

Install [mise](https://mise.jdx.dev) to get the pinned JDK, then point Gradle at
an Android SDK — either via `ANDROID_HOME` or a `local.properties` containing
`sdk.dir=/path/to/Android/sdk`.

```bash
mise install
./gradlew assembleFossDebug
```

Alternatively, open the repo in VS Code and choose **Reopen in Container**. The
[`.devcontainer/`](.devcontainer) setup provisions a Debian base with JDK 21 plus
the Android SDK platform and build-tools this project needs. That image is built
and asserted on in CI whenever `.devcontainer/` changes.

`just install` reads an optional `android.device.serial=` line from
`local.properties` to target a specific device when several are attached.

### Native builds in Termux

Keep the checkout and Gradle cache in Termux's private home directory. Use its
Android-native OpenJDK 21 with `-PtermuxBuild=true`; this removes only the Temurin
vendor requirement, not the Java 21 requirement. Desktop and CI builds keep the
Temurin pin by default.

An API 37 SDK platform, licensed SDK metadata and Java tools are still required.
Provide AGP's selected build-tools version as well (currently `36.0.0`).
Desktop Linux SDK executables do not run on Android: provide Android-native
`aapt2` and `zipalign` instead (`pkg install aapt aapt2` in Termux). Select the
resource compiler explicitly:

```bash
bash ./gradlew assembleFossDebug -PtermuxBuild=true \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" \
  -Dorg.gradle.native=false -Dorg.gradle.vfs.watch=false --max-workers=2
```

Add `-PdebugApplicationIdSuffix=.tabletdev` to install debug builds alongside an
existing app signed by another development machine. This keeps its data and
wallpaper widget intact; launcher shortcuts target the selected debug package.
Without this property the application ID is unchanged.

Use the project's Gradle wrapper and keep Git history available for the generated
version code. Android-native SDK tools must be validated against this project's
AGP and platform versions; merely installing Java and Gradle is not sufficient.

On a workstation with the `tablet-dev` USB helpers configured, the complete loop
from the repository root is:

```bash
tablet-dev loop . assembleFossDebug \
  app/build/outputs/apk/foss/debug/app-foss-debug.apk \
  com.cascadiacollections.bauhaus.tabletdev \
  -PdebugApplicationIdSuffix=.tabletdev
```

This synchronizes source files, compiles on the tablet, retrieves the signed APK,
and installs and launches the separate development app. Source synchronization
does not delete device-only files or update Git history.

## Release signing

Release builds are signed only when signing material is present. Without it the
release APK is produced **unsigned** — and an unsigned APK cannot be installed.

Locally, copy [`keystore.properties.example`](keystore.properties.example) to
`keystore.properties` (it is git-ignored) and fill in `storeFile`, `storePassword`,
`keyAlias`, and `keyPassword`.

In CI the equivalent environment variables take precedence: `KEYSTORE_PATH`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Project Structure

| Module | Description |
|--------|-------------|
| `:app` | Main application |
| `:benchmark` | Macrobenchmark tests and baseline profile generation |

## Service contract

Two properties of the bauhaus service's contract shape this code and are easy to
get wrong:

- **Artwork is keyed by UTC date**, published at 04:00 UTC. The device clock names
  a different day than the service does for part of every day, so the app never
  calls `LocalDate.now()`. It seeds from `serviceToday()` and then anchors to the
  `date` the service itself returns in `/api/today.json`. See
  [`ServiceCalendar.kt`](app/src/main/java/com/cascadiacollections/bauhaus/data/ServiceCalendar.kt).
- **Image responses `Vary: Accept`.** Every image request must carry the same
  `Accept` header or Coil and the API layer cache-miss each other and double the
  request count. An OkHttp interceptor enforces this — see
  [`HttpModule.kt`](app/src/main/java/com/cascadiacollections/bauhaus/data/HttpModule.kt).

## License

[MIT](LICENSE) — Cascadia Collections

Artwork is not covered by that licence: images from the daily scheduled runs are
CC0-1.0, and each image's own licence and upstream source are published in its
metadata and shown in the app.
