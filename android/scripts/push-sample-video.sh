#!/usr/bin/env zsh
# Push a sample video onto the emulator/device and force MediaStore indexing.
# Usage: ./scripts/push-sample-video.sh [path-to.mp4]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/env.sh" 2>/dev/null || true

VIDEO="${1:-$ROOT/../uploads/samples/wechat_test_e80ed57c.mp4}"
if [[ ! -f "$VIDEO" ]]; then
  echo "Video not found: $VIDEO" >&2
  exit 1
fi

NAME="wechat_test_badminton.mp4"
echo "==> Push $VIDEO"
adb shell mkdir -p /sdcard/Download /sdcard/Movies /sdcard/DCIM/Camera
adb push "$VIDEO" "/sdcard/Download/$NAME"
adb push "$VIDEO" "/sdcard/Movies/$NAME"
adb push "$VIDEO" "/sdcard/DCIM/Camera/$NAME"

echo "==> Media scan"
for path in \
  "file:///sdcard/Download/$NAME" \
  "file:///sdcard/Movies/$NAME" \
  "file:///sdcard/DCIM/Camera/$NAME"
do
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "$path" >/dev/null || true
done
adb shell "content call --uri content://media/external/file --method scan_volume --arg external" >/dev/null 2>&1 || true

echo "==> MediaStore entries"
adb shell "content query --uri content://media/external/video/media --projection _display_name:relative_path" 2>/dev/null | head -20

echo "Done. In app use 「选文件」→ Downloads / Movies → $NAME"
echo "Or 「相册」and switch to Videos/Albums if Photo Picker is empty."
