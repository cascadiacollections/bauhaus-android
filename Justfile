# bauhaus-android developer recipes

# Build debug APK
build:
    ./gradlew assembleDebug --no-daemon

# Run unit tests
test:
    ./gradlew testDebugUnitTest --no-daemon

# Install debug build on connected device
install:
    #!/usr/bin/env bash
    set -euo pipefail
    serial=$(grep -m1 '^android.device.serial=' local.properties 2>/dev/null \
        | cut -d= -f2 | tr -d '[:space:]' || true)
    if [[ -n "$serial" ]]; then
        ANDROID_SERIAL="$serial" ./gradlew :app:installDebug --no-daemon
    else
        ./gradlew :app:installDebug --no-daemon
    fi

# Build + install in one step
deploy: build install
