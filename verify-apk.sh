#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
apk="${1:-app/build/outputs/apk/debug/app-debug.apk}"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH="$JAVA_HOME/bin:$PATH"
unzip -t "$apk"
/root/workspace/android-arm64-toolchain/sdk/build-tools/34.0.0/apksigner verify --verbose --print-certs "$apk"
/usr/lib/android-sdk/build-tools/debian/aapt2 dump badging "$apk"
zipalign -c -v 4 "$apk"
sha256sum "$apk"
