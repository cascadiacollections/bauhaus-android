# bauhaus-android developer recipes

# Default flavor for local development
flavor := "foss"

# Build debug APK
build:
    ./gradlew assemble{{capitalize(flavor)}}Debug --no-daemon

# Run unit tests
test:
    ./gradlew test{{capitalize(flavor)}}DebugUnitTest --no-daemon

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
