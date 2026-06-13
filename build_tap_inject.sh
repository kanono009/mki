#!/usr/bin/env bash
# build_tap_inject.sh
# Run this on a machine with Android NDK installed.
# Produces two binaries placed in app/src/main/assets/
#
# Usage:
#   export NDK=/path/to/android-ndk-r25c
#   bash build_tap_inject.sh

set -e

NDK=${NDK:-$ANDROID_NDK_HOME}
if [ -z "$NDK" ]; then
  echo "Set NDK env var to your Android NDK root"
  exit 1
fi

SRC="app/src/main/cpp/tap_inject.c"
OUT="app/src/main/assets"
mkdir -p "$OUT"

# ── arm64-v8a ────────────────────────────────────────────────────────────────
CC64="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
$CC64 -O2 -static -o "$OUT/tap_inject_arm64" "$SRC"
echo "Built: $OUT/tap_inject_arm64"

# ── armeabi-v7a ──────────────────────────────────────────────────────────────
CC32="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi26-clang"
$CC32 -O2 -static -o "$OUT/tap_inject_arm32" "$SRC"
echo "Built: $OUT/tap_inject_arm32"

echo "Done. Place both files in app/src/main/assets/ before building the APK."
