#!/usr/bin/env zsh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/env.sh"
cd "$ROOT"
./gradlew :app:testDebugUnitTest "$@"
