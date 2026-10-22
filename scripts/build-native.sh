#!/usr/bin/env bash
# Builds a minimal static libmgba and the gbalink host binary into ./bin.
set -euo pipefail
cd "$(dirname "$0")/.."

MGBA=vendor/mgba
if [ ! -d "$MGBA" ]; then
	git clone --depth 1 https://github.com/mgba-emu/mgba.git "$MGBA"
fi

if [ ! -f "$MGBA/build/libmgba.a" ]; then
	cmake -S "$MGBA" -B "$MGBA/build" -DCMAKE_BUILD_TYPE=Release \
		-DBUILD_QT=OFF -DBUILD_SDL=OFF -DBUILD_STATIC=ON -DBUILD_SHARED=OFF \
		-DBUILD_GL=OFF -DBUILD_GLES2=OFF -DBUILD_GLES3=OFF -DUSE_EPOXY=OFF \
		-DUSE_FFMPEG=OFF -DUSE_ZLIB=OFF -DUSE_MINIZIP=OFF -DUSE_PNG=OFF -DUSE_LIBZIP=OFF \
		-DUSE_SQLITE3=OFF -DUSE_ELF=OFF -DUSE_LUA=OFF -DUSE_JSON_C=OFF -DUSE_FREETYPE=OFF \
		-DUSE_LZMA=OFF -DUSE_DISCORD_RPC=OFF -DUSE_EDITLINE=OFF -DUSE_DEBUGGERS=OFF \
		-DM_CORE_GB=OFF -DENABLE_SCRIPTING=OFF
	cmake --build "$MGBA/build" -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
fi

LIBS=(-lpthread -lm)
if [ "$(uname)" = Darwin ]; then
	LIBS+=(-framework CoreFoundation)
fi

# Use exactly the defines libmgba was compiled with, or struct layouts won't match.
read -r -a DEFINES <<< "$(sed -n 's/^C_DEFINES = //p' "$MGBA/build/CMakeFiles/mgba.dir/flags.make")"

mkdir -p bin
CFLAGS=(-O2 -std=gnu11 -Wall -DNDEBUG "${DEFINES[@]}" -I"$MGBA/include" -I"$MGBA/build/include" -I"$MGBA/src" -Inative)
# TouchNav = the core (touchnav.c) + one file per screen area (touchnav_<area>.c).
TOUCHNAV=(native/touchnav*.c)
cc "${CFLAGS[@]}" native/gbalink.c native/netlink.c "${TOUCHNAV[@]}" "$MGBA/build/libmgba.a" "${LIBS[@]}" -o bin/gbalink
echo "Built bin/gbalink"
# Headless probe for exploring menus and testing touch navigation; same flags (see that script).
scripts/build-probe.sh native bin/gbaprobe
