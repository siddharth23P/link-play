#!/usr/bin/env bash
# Builds gbaprobe from any copy of the native/ directory:
#   scripts/build-probe.sh <native_dir> <out_binary>
# Compiles <native_dir>/gbaprobe.c and every <native_dir>/touchnav*.c against the repo's
# vendor/mgba/build/libmgba.a with the same flags as scripts/build-native.sh (which uses
# this script for bin/gbaprobe). Working on a private copy of native/ keeps a
# half-written touch area from breaking anyone else's build.
set -euo pipefail

if [ $# -ne 2 ]; then
	echo "usage: $0 <native_dir> <out_binary>" >&2
	exit 2
fi
NATIVE=$(cd "$1" && pwd)
OUT_DIR=$(mkdir -p "$(dirname "$2")" && cd "$(dirname "$2")" && pwd)
OUT="$OUT_DIR/$(basename "$2")"

cd "$(dirname "$0")/.."
MGBA=$PWD/vendor/mgba
if [ ! -f "$MGBA/build/libmgba.a" ]; then
	echo "$MGBA/build/libmgba.a is missing: run scripts/build-native.sh once first" >&2
	exit 1
fi

LIBS=(-lpthread -lm)
if [ "$(uname)" = Darwin ]; then
	LIBS+=(-framework CoreFoundation)
fi

# Use exactly the defines libmgba was compiled with, or struct layouts won't match.
read -r -a DEFINES <<< "$(sed -n 's/^C_DEFINES = //p' "$MGBA/build/CMakeFiles/mgba.dir/flags.make")"

CFLAGS=(-O2 -std=gnu11 -Wall -DNDEBUG "${DEFINES[@]}" -I"$MGBA/include" -I"$MGBA/build/include" -I"$MGBA/src" -I"$NATIVE")
TOUCHNAV=("$NATIVE"/touchnav*.c)
# Headless probe for exploring menus and testing touch navigation (PNG shots need zlib).
cc "${CFLAGS[@]}" "$NATIVE/gbaprobe.c" "${TOUCHNAV[@]}" "$MGBA/build/libmgba.a" "${LIBS[@]}" -lz -o "$OUT"
echo "Built $OUT"
