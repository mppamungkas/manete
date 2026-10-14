#!/bin/sh
# Builds and runs the host-side C tests. Usage: run_host_tests.sh <cmake bin dir> <build dir>
# Called by the Gradle task :feature:capture:nativeHostTest; can also be run by hand.
set -eu
CMAKE_BIN="$1"
BUILD="$2"
SRC="$(cd "$(dirname "$0")" && pwd)"
"$CMAKE_BIN/cmake" -S "$SRC" -B "$BUILD" -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" -DCMAKE_BUILD_TYPE=Debug > "$BUILD.configure.log" 2>&1 \
    || { cat "$BUILD.configure.log"; exit 1; }
"$CMAKE_BIN/cmake" --build "$BUILD"
"$CMAKE_BIN/ctest" --test-dir "$BUILD" --output-on-failure
