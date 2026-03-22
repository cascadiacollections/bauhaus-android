# Bauhaus

A daily artwork wallpaper app for Android. Each day, Bauhaus fetches a new artwork image and lets you set it as your device wallpaper.

## Features

- Daily artwork delivered via [Cloudflare Workers CDN](https://bauhaus.cascadiacollections.workers.dev)
- Set artwork as home screen, lock screen, or both wallpapers
- Browse archived artworks by date
- Background updates via WorkManager
- Baseline profile for optimized startup performance

## Tech Stack

- Jetpack Compose with Material 3
- Coil 3 for image loading
- OkHttp 5
- WorkManager for background wallpaper updates
- DataStore Preferences
- kotlinx-serialization

## Requirements

- Android 15+ (API 35)
- compileSdk / targetSdk 36
- AGP 9.1.0, Kotlin 2.3.20

## Build

```bash
# Build debug APK
./gradlew assembleDebug

# Run tests
./gradlew testDebugUnitTest

# Install on connected device
./gradlew installDebug
```

Or using [Just](https://github.com/casey/just):

```bash
just build
just test
just install
```

## Project Structure

| Module | Description |
|--------|-------------|
| `:app` | Main application |
| `:benchmark` | Macrobenchmark tests and baseline profile generation |

## License

[MIT](LICENSE) — Cascadia Collections
