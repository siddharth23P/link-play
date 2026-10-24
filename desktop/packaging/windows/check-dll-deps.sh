#!/bin/sh
# Fails when gbalink.dll needs any DLL that is not part of Windows itself.
#
# The JVM loads the DLL by its full path, but Windows looks for the DLL's own dependencies in the exe
# folder, the system folders and PATH, never next to the DLL. A MinGW runtime DLL (libgcc_s_seh-1.dll,
# libwinpthread-1.dll, libstdc++-6.dll ...) would make System.loadLibrary fail on every computer that
# has no MSYS2, so the DLL has to be linked statically.
#
# usage: check-dll-deps.sh path/to/gbalink.dll      (needs objdump: MSYS2 UCRT64 binutils has it)
#        objdump -p gbalink.dll | check-dll-deps.sh -
set -eu

if [ "${1:--}" = "-" ]; then
	dump=$(cat)
else
	dump=$(objdump -p "$1")
fi

names=$(printf '%s\n' "$dump" | sed -n 's/^.*DLL Name: *\([^ ]*\).*$/\1/p')
if [ -z "$names" ]; then
	echo "check-dll-deps: found no 'DLL Name:' lines, is this a Windows DLL?" >&2
	exit 1
fi

echo "gbalink.dll imports:"
printf '%s\n' "$names" | sed 's/^/  /'

bad=$(printf '%s\n' "$names" | grep -i -E '^(lib|msys|mingw|vcruntime|msvcp|msvcr[0-9])' || true)
if [ -n "$bad" ]; then
	echo "check-dll-deps: FAIL, these are not Windows system DLLs and will be missing on other computers:" >&2
	printf '%s\n' "$bad" | sed 's/^/  /' >&2
	exit 1
fi
# Temurin 17 uses the Universal C Runtime (ucrtbase, api-ms-win-crt-*); msvcrt.dll means the MINGW64
# environment was used by mistake.
if printf '%s\n' "$names" | grep -q -i -x 'msvcrt\.dll'; then
	echo "check-dll-deps: FAIL, gbalink.dll uses the old msvcrt.dll. Build it in the MSYS2 UCRT64 environment." >&2
	exit 1
fi
echo "check-dll-deps: OK, only Windows system DLLs"
