#!/usr/bin/env bash
# Builds app/libs/tsbridge.aar from tsbridge/ with gomobile.
#
# The module is copied to a fixed temporary directory and built there.
# gomobile records the module's directory in the library's build info, and
# -trimpath does not remove it, so building from a fixed path keeps the output
# independent of where the repository is checked out.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
work="${WORK_DIR:-/tmp/kdec-aar-build}"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is not set (see docs/building.md)}"
command -v gomobile >/dev/null || { echo "gomobile not found on PATH (see docs/building.md)" >&2; exit 1; }

rm -rf "$work"; mkdir -p "$work"
cp -r "$here/tsbridge" "$work/tsbridge"
rm -f "$work/tsbridge"/*.aar "$work/tsbridge"/*-sources.jar

# -s -w                  strip Go symbol tables (about half the size)
# max-page-size=16384    16 KB ELF alignment, required on 16 KB page devices
#                        (Android 15+). Check with: readelf -lW libgojni.so
cd "$work/tsbridge"
GOFLAGS=-trimpath gomobile bind -trimpath \
  -target=android/arm64 -androidapi 26 \
  -ldflags "-s -w -extldflags=-Wl,-z,max-page-size=16384" \
  -o "$work/tsbridge.aar" .

mkdir -p "$here/app/libs"
cp "$work/tsbridge.aar" "$here/app/libs/tsbridge.aar"
rm -rf "$work"

echo "built app/libs/tsbridge.aar"
