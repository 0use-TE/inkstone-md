#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/root/workspace/android-arm64-toolchain/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=/root/workspace/android-arm64-toolchain/gradle-home
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
exec /root/workspace/android-arm64-toolchain/gradle-8.9/bin/gradle --no-daemon --console=plain :app:assembleDebug "$@"
