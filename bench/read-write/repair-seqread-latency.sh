#!/usr/bin/env bash
#
# bench/read-write/repair-seqread-latency.sh -- re-measure the cells whose JUring latency
# was corrupted by a latency-slot collision in the benchmark harness.
#
# Defect (fixed in bench/juring/JUringFileBench.java this session): the harness
# derived its per-op latency slot from the low bits of user_data, assuming the low
# bits are a monotonic op id. That holds for fixed-buffer ops, but non-fixed
# READ_EXT encodes the buffer ADDRESS: arena buffers are 4096-aligned slices, so
# every in-flight op collided on the same start-timestamp slot and completions
# read a later op's start time -> latency understated (p99 < implied mean).
# Affected cells: exactly the JUring cells with io_buffer_mode=arena-unregistered,
# i.e. seqread-1m-qd8 (1MiB QD8) and seqread-1m-qd32 (1MiB QD32). IOPS/bytes/errors were unaffected.
#
# This script archives the invalid JSON/logs under the directory named by
# ARCH_DIR (default bench/results/_invalid/read-write/latency-slot-collision,
# outside the session dir so the analyzer does not recurse into them).
# and re-runs the same 10 cells (same protocol: 10 s warmup + 30 s, R=5, seed,
# CPU 2) with the fixed harness, appending fresh rows to cell-meta.tsv.
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=bench/read-write/lib.sh
source "$HERE/lib.sh"
cd "$MATRIX_REPO_ROOT"

ARCH=${ARCH_DIR:-$MATRIX_REPO_ROOT/bench/results/_invalid/read-write/latency-slot-collision}
mkdir -p "$ARCH"
echo "harness sha256: $(sha256sum bench/juring/JUringFileBench.java | cut -d' ' -f1)"
echo "harness classes: $(sha256sum bench/juring/out/bench/juring/JUringFileBench.class | cut -d' ' -f1)"
echo "archiving invalid originals under $ARCH"

preflight
init_meta
started=$(now_utc)
for spec in "8 seqread-1m-qd8" "32 seqread-1m-qd32"; do
    read -r qd case_id <<<"$spec"
    for rep in 1 2 3 4 5; do
        id="${case_id}__juring__std__bs1m__qd${qd}__t1__r${rep}"
        json="$MATRIX_RESULTS/cells/$id.json"
        log="$MATRIX_RESULTS/logs/$id.log"
        if [[ -f $json ]]; then mv -f "$json" "$ARCH/"; fi
        if [[ -f $log ]]; then mv -f "$log" "$ARCH/"; fi
        build_engine_cmd juring seqread 1m "$qd" 30 10 "$id" "$json" std
        run_cell "$case_id" juring std 1m "$qd" "$rep" "$log" "$json" -- "${MATRIX_CMD[@]}" ||
            echo "warn: repair cell failed ($id)" >&2
    done
done
echo "repair done: started $started, finished $(now_utc)"
echo "repaired cells now in $MATRIX_RESULTS/cells/; invalid originals in $ARCH/"
