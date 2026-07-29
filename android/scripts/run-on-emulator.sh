#!/usr/bin/env zsh
# Build debug APK, boot emulator if needed, install and launch.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/env.sh"

AVD_NAME="${AVD_NAME:-Pixel_7_API_34}"
PKG="icu.yuqiuyijiaren.xiaobai.debug"
ACTIVITY="icu.yuqiuyijiaren.xiaobai.MainActivity"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
SAMPLE="${SAMPLE_VIDEO:-$ROOT/../uploads/m3_match.mp4}"

has_device() {
  adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'
}

if ! has_device; then
  if ! avdmanager list avd 2>/dev/null | grep -q "Name: $AVD_NAME"; then
    echo "AVD $AVD_NAME not found. Run: android/scripts/setup-emulator.sh" >&2
    exit 1
  fi
  echo "==> Starting emulator $AVD_NAME"
  nohup emulator -avd "$AVD_NAME" -netdelay none -netspeed full \
    >"/tmp/emulator-${AVD_NAME}.log" 2>&1 &
  adb wait-for-device
  echo "==> Waiting for boot"
  for _ in {1..90}; do
    booted="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$booted" == "1" ]]; then
      break
    fi
    sleep 2
  done
  if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" != "1" ]]; then
    echo "Emulator did not finish booting. See /tmp/emulator-${AVD_NAME}.log" >&2
    exit 1
  fi
fi

echo "==> Unit tests + assembleDebug"
(cd "$ROOT" && ./gradlew :app:testDebugUnitTest :app:assembleDebug)

echo "==> Install"
adb install -r "$APK"

if [[ -f "$SAMPLE" ]]; then
  echo "==> Push sample video: $SAMPLE"
  adb shell mkdir -p /sdcard/Download
  adb push "$SAMPLE" /sdcard/Download/xiaobai_sample.mp4 || true
fi

echo "==> Launch $PKG"
adb shell am start -n "$PKG/$ACTIVITY"
echo "Logs: adb logcat | grep -i xiaobai"
