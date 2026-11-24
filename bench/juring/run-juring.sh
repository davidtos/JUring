#!/usr/bin/env bash
#
# run-juring.sh - compile and run bench/juring/JUringFileBench.java.
#
# Usage (from anywhere; the script locates the repo root itself):
#
#   bench/juring/run-juring.sh --workload randread \
#       --file /mnt/TestData/read_files/single_file_bench.bin \
#       --bs 4k --qd 64 --threads 1 --seconds 5 --warmup-sec 1 --readonly \
#       --label randread_4k_qd64
#
# The JSON report is printed on stdout; progress/diagnostics go to stderr.
#
# Environment:
#   JURING_SKIP_COMPILE=1   skip `mvn compile` + `javac` (reuse bench/juring/out)
#   JURING_SKIP_MVN=1       skip only `mvn compile` (still run javac)
#   JURING_JAVA_OPTS="..."  extra JVM options (word-split; e.g. "-Xmx4g")
#   JAVA_BIN=...            java launcher (default: java from PATH)
#   JAVAC_RELEASE=25        --release value for javac (default 25; JDK 26+ required)
#   JURING_GIT_COMMIT / JURING_GIT_DIRTY / JURING_GIT_VERSION
#                           override the manifest values (auto-detected otherwise)
#
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
cd "$REPO_ROOT"

CLASSES=target/classes
OUT_DIR=bench/juring/out
JAVA_BIN=${JAVA_BIN:-java}
JAVAC_RELEASE=${JAVAC_RELEASE:-25}

if [[ "${JURING_SKIP_COMPILE:-0}" != "1" ]]; then
    if [[ "${JURING_SKIP_MVN:-0}" != "1" ]]; then
        # Offline build; target/classes must be current before javac links against it.
        mvn -o -q -DskipTests compile
    fi
    mkdir -p "$OUT_DIR"
    javac -cp "$CLASSES" -d "$OUT_DIR" --release "$JAVAC_RELEASE" \
        bench/juring/JUringFileBench.java
fi

# Reproducibility manifest. Shell-injected so the JVM does not have to guess.
export JURING_GIT_COMMIT=${JURING_GIT_COMMIT:-$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}
if [[ -z "${JURING_GIT_DIRTY:-}" ]]; then
    if [[ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null | head -n 1)" ]]; then
        export JURING_GIT_DIRTY=true
    else
        export JURING_GIT_DIRTY=false
    fi
fi
export JURING_GIT_VERSION=${JURING_GIT_VERSION:-$(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo unknown)}

JVM_ARGS=()
if [[ -n "${JURING_JAVA_OPTS:-}" ]]; then
    # shellcheck disable=SC2206
    JVM_ARGS=(${JURING_JAVA_OPTS})
fi

exec "$JAVA_BIN" --enable-native-access=ALL-UNNAMED "${JVM_ARGS[@]}" \
    -cp "$CLASSES:$OUT_DIR" bench.juring.JUringFileBench "$@"
