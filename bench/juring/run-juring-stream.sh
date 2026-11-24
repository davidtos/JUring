#!/usr/bin/env bash
#
# run-juring-stream.sh - compile and run bench/juring/JUringStreamBench.java.
#
# Usage (from anywhere; the script locates the repo root itself):
#
#   bench/juring/run-juring-stream.sh --workload stream_read \
#       --file /mnt/TestData/read_files/single_file_bench.bin \
#       --bs 128k --qd 8 --size 1g --seconds 5 --warmup-sec 2 --runs 1 --readonly \
#       --label stream_read_128k_qd8
#
# The JSON report is printed on stdout; progress/diagnostics go to stderr.
#
# Environment (mirrors bench/juring/run-juring.sh):
#   JURING_SKIP_COMPILE=1   skip `mvn compile` + `javac` (reuse bench/juring/out)
#   JURING_SKIP_MVN=1       skip only `mvn compile` (still run javac)
#   JURING_JAVA_OPTS="..."  extra JVM options (word-split; e.g. "-Xmx4g")
#   JAVA_BIN=...            java launcher (default: java from PATH)
#   JAVAC_RELEASE=25        --release value for javac (default 25; JDK 26+ required)
#   JURING_GIT_COMMIT / JURING_GIT_DIRTY / JURING_GIT_VERSION
#                           override the manifest values (auto-detected otherwise)
#
# Note: the stream API (JuringInputStream/JuringOutputStream/InputStreamOptions/
# OutputStreamOptions) must be compiled into target/classes first. JURING_SKIP_MVN=1
# compiles the benchmark against the existing target/classes without touching Maven
# (Maven is not safe to run concurrently; the Lead owns the main build).
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
        bench/juring/JUringStreamBench.java
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
    -cp "$CLASSES:$OUT_DIR" bench.juring.JUringStreamBench "$@"
