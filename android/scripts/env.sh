#!/usr/bin/env zsh
# Source this file:  source android/scripts/env.sh
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
if [[ ! -d "$JAVA_HOME" ]]; then
  # Alternate Homebrew layout
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
fi

export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

CT="$(ls -d "$ANDROID_HOME"/cmdline-tools/*/bin 2>/dev/null | head -1 || true)"
export PATH="$JAVA_HOME/bin:${CT:-$ANDROID_HOME/cmdline-tools/latest/bin}:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
java -version 2>&1 | head -1
command -v adb >/dev/null && adb version | head -1 || echo "adb: missing"
command -v sdkmanager >/dev/null && echo "sdkmanager: ok" || echo "sdkmanager: missing"
