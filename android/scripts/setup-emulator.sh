#!/usr/bin/env zsh
# One-time (or idempotent) install of emulator + AVD for Mac Apple Silicon.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/env.sh"

AVD_NAME="${AVD_NAME:-Pixel_7_API_34}"
SYS_IMG="system-images;android-34;google_apis;arm64-v8a"

echo "==> Accepting licenses"
yes | sdkmanager --licenses >/dev/null || true

echo "==> Installing emulator + system image (arm64)"
sdkmanager --install "emulator" "platform-tools" "platforms;android-34" "build-tools;34.0.0" "$SYS_IMG"

if ! avdmanager list avd 2>/dev/null | grep -q "Name: $AVD_NAME"; then
  echo "==> Creating AVD $AVD_NAME"
  echo no | avdmanager create avd \
    -n "$AVD_NAME" \
    -k "$SYS_IMG" \
    -d pixel_7 \
    --force
else
  echo "==> AVD $AVD_NAME already exists"
fi

CFG="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
if [[ -f "$CFG" ]]; then
  # Best-effort GPU / RAM for laptops
  grep -q '^hw.ramSize=' "$CFG" || echo 'hw.ramSize=4096' >>"$CFG"
  grep -q '^hw.gpu.enabled=' "$CFG" || echo 'hw.gpu.enabled=yes' >>"$CFG"
  grep -q '^hw.gpu.mode=' "$CFG" || echo 'hw.gpu.mode=auto' >>"$CFG"
fi

echo "Done. Start with: android/scripts/run-on-emulator.sh"
