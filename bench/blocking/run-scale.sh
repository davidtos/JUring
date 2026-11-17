#!/usr/bin/env bash
#
# run-scale.sh -- opt-in 1,000,000-virtual-thread scale run for JUringBlocking.
#
# The committed suite runs JUringBlockingScaleTest with 10,000 virtual threads
# (see docs/vt-scale.md). This script runs the same test with 1,000,000 virtual
# threads and a 6 GiB heap for the forked test JVM. It is opt-in: it is not part
# of the default build, it can take minutes, and it needs enough heap for the
# virtual threads and their buffers on top of the library's call records.
#
# Usage (from anywhere; the script locates the repo root itself):
#
#   bench/blocking/run-scale.sh
#
# Optional environment overrides:
#   JURING_SCALE_VT       virtual threads to start          (default 1000000)
#   JURING_SCALE_XMX      heap for the forked test JVM      (default 6g)
#   JURING_SCALE_WORKERS  poller workers for the estimate   (default 4)
#
# The printed estimate is model arithmetic from BlockingOptions.estimatedBytes()
# (88 bytes per pre-allocated call record plus 4 KiB per worker), for a budget
# that admits every virtual thread at once, not a measurement. The test itself
# sizes its own budget (it deliberately exercises the admission path) and prints
# the exact estimate it used ("bytes estimated") in its output.
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
cd "$REPO_ROOT"

VT=${JURING_SCALE_VT:-1000000}
XMX=${JURING_SCALE_XMX:-6g}
WORKERS=${JURING_SCALE_WORKERS:-4}

next_pow2() {
    local v=$1 p=1
    while (( p < v )); do p=$(( p * 2 )); done
    printf '%s' "$p"
}

# Capacity model for a budget that admits every virtual thread at once
# (maxConcurrentCalls = VT): split over workers, round each share up to a power
# of two with the 256-record per-worker minimum, then add the per-worker
# overhead. See docs/vt-scale.md.
per_worker=$(( (VT + WORKERS - 1) / WORKERS ))
(( per_worker < 256 )) && per_worker=256
per_worker=$(next_pow2 "$per_worker")
records=$(( per_worker * WORKERS ))
estimate_bytes=$(( records * 88 + WORKERS * 4096 ))
estimate_mib=$(awk -v b="$estimate_bytes" 'BEGIN { printf "%.1f", b / 1048576 }')
estimate_mb=$(awk -v b="$estimate_bytes" 'BEGIN { printf "%.1f", b / 1000000 }')

printf 'JUringBlocking scale run\n'
printf '  virtual threads: %s\n' "$VT"
printf '  call-record estimate (maxConcurrentCalls = %s, %s workers):\n' "$VT" "$WORKERS"
printf '    %s records/worker x %s workers = %s records\n' "$per_worker" "$WORKERS" "$records"
printf '    %s records x 88 B + %s x 4096 B = %s bytes (~%s MiB / ~%s MB)\n' \
    "$records" "$WORKERS" "$estimate_bytes" "$estimate_mib" "$estimate_mb"
printf '  caller buffers, virtual-thread stacks and other heap are extra; -Xmx%s is for this run.\n' "$XMX"
printf '  (the test sizes its own budget and prints the exact estimate it uses as "bytes estimated")\n'
printf '\n'

FORK_ARGS="-Xmx${XMX} -Djuring.scale.vt=${VT}"
printf 'mvn -o -Dmaven.repo.local=.m2repo -Dtest=JUringBlockingScaleTest -Djuring.surefire.argLine="%s" test\n\n' "$FORK_ARGS"

exec mvn -o -Dmaven.repo.local=.m2repo -Dtest=JUringBlockingScaleTest \
    -Djuring.surefire.argLine="$FORK_ARGS" test
