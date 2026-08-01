# bauhaus-android developer recipes
#
# Every Gradle task in this project is flavored — there is no bare
# `assembleDebug` or `testDebugUnitTest`. Recipes default to `foss`; override
# per-invocation with `just flavor=full build`.

# Default flavor for local development
flavor := "foss"

# List available recipes
default:
    @just --list

# Build debug APK
build:
    ./gradlew assemble{{capitalize(flavor)}}Debug --no-daemon

# Run unit tests
test:
    ./gradlew test{{capitalize(flavor)}}DebugUnitTest --no-daemon

# Run lint (warnings are errors)
lint:
    ./gradlew lint{{capitalize(flavor)}}Debug --no-daemon

# Generate the JaCoCo coverage report (app/build/reports/jacoco/)
coverage:
    ./gradlew jacocoTestReport --no-daemon

# Everything CI gates on, in one invocation
check:
    ./gradlew assemble{{capitalize(flavor)}}Release assemble{{capitalize(flavor)}}Debug \
        lint{{capitalize(flavor)}}Debug test{{capitalize(flavor)}}DebugUnitTest jacocoTestReport \
        --no-daemon --parallel --build-cache

# Install debug build on connected device
install:
    #!/usr/bin/env bash
    set -euo pipefail
    serial=$(grep -m1 '^android.device.serial=' local.properties 2>/dev/null \
        | cut -d= -f2 | tr -d '[:space:]' || true)
    if [[ -n "$serial" ]]; then
        ANDROID_SERIAL="$serial" ./gradlew :app:install{{capitalize(flavor)}}Debug --no-daemon
    else
        ./gradlew :app:install{{capitalize(flavor)}}Debug --no-daemon
    fi

# Build + install in one step
deploy: build install

# Build FOSS variant
build-foss:
    ./gradlew assembleFossDebug --no-daemon

# Build full variant (with Firebase)
build-full:
    ./gradlew assembleFullDebug --no-daemon

# Run the macrobenchmarks / regenerate the baseline profile.
# Requires a connected device or running emulator — there is no Gradle-managed
# device configured for :benchmark.
benchmark:
    ./gradlew :benchmark:connectedBenchmarkAndroidTest --no-daemon

# Refresh gradle/libs.versions.toml to the newest resolvable versions.
# Review the diff before committing — this will happily pull in prereleases.
deps:
    ./gradlew versionCatalogUpdate --no-daemon
