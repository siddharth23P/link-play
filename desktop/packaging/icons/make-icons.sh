#!/bin/sh
# Regenerates every desktop icon from the launcher geometry (IconGen.java). Needs a JDK; the .icns step
# needs macOS (iconutil), so run it on a Mac. Commit the results.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
desktop=$(cd "$here/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/LinkPlay.iconset"

java "$here/IconGen.java" "$desktop" "$work/LinkPlay.iconset"

if command -v iconutil >/dev/null 2>&1; then
	iconutil -c icns "$work/LinkPlay.iconset" -o "$desktop/packaging/macos/LinkPlay.icns"
	echo "wrote packaging/macos/LinkPlay.icns"
else
	echo "iconutil not found: kept the existing LinkPlay.icns (run this on a Mac to rebuild it)" >&2
fi
