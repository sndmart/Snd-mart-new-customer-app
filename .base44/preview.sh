#!/usr/bin/env bash
# Base44 preview entrypoint: build the Android project, (re)generate the
# Roborazzi Compose screenshot(s), then serve them on port 3000.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

SHOTS_DIR="app/src/test/screenshots"
mkdir -p "$SHOTS_DIR"

# If screenshots or index don't exist yet, run tests to generate them
if [ ! -f "$SHOTS_DIR/index.html" ] || [ ! -f "$SHOTS_DIR/greeting.png" ]; then
  echo "=== Generating Roborazzi Compose screenshot(s) ==="
  gradle :app:testDebugUnitTest \
    --tests "com.example.GreetingScreenshotTest" \
    --no-daemon --stacktrace || true
fi

echo "=== Serving screenshots gallery on port 3000 ==="
exec python3 -m http.server 3000 --directory "$SHOTS_DIR" --bind 0.0.0.0

