#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-$PROJECT_ROOT/.venv312/bin/python}"

"$PYTHON_BIN" "$PROJECT_ROOT/desktop/assets/generate_icon.py"
cd "$PROJECT_ROOT"
"$PYTHON_BIN" -m PyInstaller --noconfirm --clean desktop/xiaobai_auto_editing.spec

printf '\nBuilt application: %s\n' "$PROJECT_ROOT/dist/小白自动剪辑.app"
