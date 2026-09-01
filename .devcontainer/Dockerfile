# Debian base is pinned to a codename, not the floating `:21` tag. The unpinned
# tag silently rolls onto the next Debian release, which changes glibc and the
# system toolchain under a container that is otherwise reproducible — and it does
# so at image-rebuild time, so two developers on the same commit can end up on
# different Debian versions.
FROM mcr.microsoft.com/devcontainers/java:25-bookworm

# Fail the image build on any failing command in a pipeline. Without this the
# `yes | sdkmanager --licenses` below reports success even when sdkmanager dies,
# producing an image with no SDK in it and a build that fails much later.
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# Keep in step with gradle/libs.versions.toml: compileSdk/targetSdk 37.
#
# ANDROID_SDK_VERSION is the cmdline-tools *build number*, not an SDK version.
# It must be recent enough to resolve the platform below — an old cmdline-tools
# build cannot see newly published platform packages, and the failure reads as
# "package not found" rather than "your tools are stale".
ARG ANDROID_SDK_VERSION=13114758
ARG ANDROID_BUILD_TOOLS=37.0.0
# Matches the package ID the CI workflow installs. Recent platforms are
# published with a minor component (`android-37.0`), and the bare `android-37`
# that used to be here is a different — possibly non-existent — package.
ARG ANDROID_PLATFORM=android-37.0

ENV ANDROID_HOME=/usr/local/android-sdk
# ANDROID_SDK_ROOT is deprecated but still what some tooling reads; set both so
# a shell in the container agrees with Gradle about where the SDK lives.
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

# Install Android command-line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_SDK_VERSION}_latest.zip" -o /tmp/cmdtools.zip \
    && unzip -q /tmp/cmdtools.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm /tmp/cmdtools.zip

# Accept licenses and install SDK components.
#
# The licence input is a finite file rather than `yes | sdkmanager`. Under
# `pipefail` that pipeline always fails: sdkmanager exits as soon as it has read
# the last prompt, `yes` then takes SIGPIPE, and the pipeline reports 141. A
# bounded stdin removes the pipe entirely, so pipefail keeps protecting the rest
# of the chain instead of having to be turned off.
#
# stderr is kept: swallowing it with 2>&1 is what let a broken licence step pass
# unnoticed. Only the (very long) accepted-licence text goes to /dev/null.
RUN printf 'y\n%.0s' $(seq 100) > /tmp/sdk-licenses \
    && sdkmanager --licenses < /tmp/sdk-licenses > /dev/null \
    && rm /tmp/sdk-licenses \
    && sdkmanager --install \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" \
        "platform-tools" \
    && sdkmanager --list_installed

# Allow the container user to install additional SDK packages without sudo
RUN chmod -R g+w ${ANDROID_HOME} && chgrp -R vscode ${ANDROID_HOME}
