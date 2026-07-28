#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_PATH="$PROJECT_ROOT/dist/小白自动剪辑.app"
VERSION="${XIAOBAI_VERSION:-1.0.0}"
ARCH="$(uname -m)"
RELEASE_DIR="$PROJECT_ROOT/release"
DMG_PATH="$RELEASE_DIR/XiaobaiAutoEditing-${VERSION}-macOS-${ARCH}.dmg"

if [[ ! -d "$APP_PATH" ]]; then
  printf 'Application not found: %s\n' "$APP_PATH" >&2
  exit 1
fi

STAGING_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGING_DIR"' EXIT

ditto "$APP_PATH" "$STAGING_DIR/小白自动剪辑.app"
ln -s /Applications "$STAGING_DIR/Applications"
mkdir -p "$RELEASE_DIR"
rm -f "$DMG_PATH"

hdiutil create \
  -volname "小白自动剪辑" \
  -srcfolder "$STAGING_DIR" \
  -ov \
  -format UDZO \
  "$DMG_PATH"

printf 'Built installer: %s\n' "$DMG_PATH"
