#!/usr/bin/env bash
#
# Build bench/seastar/seastar_stream_bench.cc against the in-repo SeaStarRef build.
#
# The sandbox has no writable /usr, so SeaStarRef was built against a local
# dependency sysroot (.sysroot/root/usr) and a locally bootstrapped CMake/Ninja
# (.tools). On a normal host you can instead use the seastar.pc from
# SeaStarRef/build/release after `sudo ./install-dependencies.sh` + configure.
#
# Usage:
#   bench/seastar/build-stream-bench.sh
#
# Environment overrides:
#   SEASTAR_REF   path to seastar checkout   (default: <repo>/SeaStarRef)
#   SYSROOT       dependency sysroot prefix  (default: <repo>/.sysroot/root/usr)
#   CXX           compiler                    (default: /usr/bin/g++)
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
SEASTAR_REF=${SEASTAR_REF:-$ROOT/SeaStarRef}
SYSROOT=${SYSROOT:-$ROOT/.sysroot/root/usr}
CXX=${CXX:-/usr/bin/g++}

BUILD=$SEASTAR_REF/build/release
if [[ ! -f "$BUILD/libseastar.a" ]]; then
    echo "error: $BUILD/libseastar.a not found; build SeaStarRef first (see build-and-run.md)" >&2
    exit 1
fi

# Definitions must match how libseastar.a was compiled (query the ninja file).
DEFINES=$(grep -m1 -o 'DEFINES = .*' "$BUILD/build.ninja" | sed 's/^DEFINES = //')

# C++ flags actually used for the library (same ninja file), minus warnings.
CXXFLAGS=$(grep -m1 -o 'FLAGS = .*' "$BUILD/build.ninja" | sed 's/^FLAGS = //' | sed 's/-Wall -Werror//g')

INCLUDES="-I$SEASTAR_REF/include -I$BUILD/gen/include -I$BUILD/gen/src -isystem $SYSROOT/include"

# Libraries required by libseastar.a (order matters: archive first, then deps).
LIBS=(
    "$BUILD/libseastar.a"
    -L"$SYSROOT/lib64" -Wl,-rpath,"$SYSROOT/lib64"
    -lboost_program_options -lboost_filesystem -lboost_thread
    -lcares -lfmt -lsctp -luring -llz4 -lprotobuf -lhwloc -lnuma
    -lssl -lcrypto -lyaml-cpp
    -lpthread -ldl -lrt -latomic
)

set -x
"$CXX" $DEFINES $CXXFLAGS $INCLUDES \
    "$SCRIPT_DIR/seastar_stream_bench.cc" \
    -o "$SCRIPT_DIR/seastar_stream_bench" \
    "${LIBS[@]}"
set +x
echo "built: $SCRIPT_DIR/seastar_stream_bench"
