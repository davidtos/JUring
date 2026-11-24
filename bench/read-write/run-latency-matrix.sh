#!/usr/bin/env bash
#
# bench/read-write/run-latency-matrix.sh -- randread-4k-qd1-latency/randread-4k-qd64-latency: 4 KiB random read latency.
#
# Protocol: 10 s warmup + 120 s measured per cell, R=5, one fresh process per
# rep, pinned to CPU 2, engines interleaved by rep. Reports p50/p95/p99/p999/max
# from the engine's own per-op timestamps (JUring and seastar: before submit ->
# completion; fio: clat, which is a different definition -- the report must say
# so and must not compare fio's tail to theirs).
#
# Env overrides: WARMUP DURATION REPS QDS ENGINES FIO_VARIANT MATRIX_RESULTS MATRIX_CPU
#
# Output: $MATRIX_RESULTS/{cells,logs}/<id>.json|.log + cell-meta.tsv
# (MATRIX_RESULTS defaults to bench/results/read-write).
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=bench/read-write/lib.sh
source "$HERE/lib.sh"
cd "$MATRIX_REPO_ROOT"

WARMUP=${WARMUP:-10}
DURATION=${DURATION:-120}
REPS=${REPS:-5}
QDS=${QDS:-"1 64"}
ENGINES=${ENGINES:-"juring seastar fio-io_uring"}
FIO_VARIANT=${FIO_VARIANT:-matched}

case_for_qd() {
    # canonical cell slug from bench/analysis/cells.py (cell_for in bench/cells.sh)
    cell_for latency randread 4k "$1" 2>/dev/null || echo "unknown-qd$1"
}

run_one() {
    local engine=$1 qd=$2 rep=$3
    local case_id variant=std
    case_id=$(case_for_qd "$qd")
    if [[ $engine == fio-* ]]; then variant=$FIO_VARIANT; fi
    local id="${case_id}__${engine}__${variant}__bs4k__qd${qd}__t1__r${rep}"
    local json="$MATRIX_RESULTS/cells/$id.json" log="$MATRIX_RESULTS/logs/$id.log"
    build_engine_cmd "$engine" randread 4k "$qd" "$DURATION" "$WARMUP" "$id" "$json" "$variant"
    run_cell "$case_id" "$engine" "$variant" 4k "$qd" "$rep" "$log" "$json" -- "${MATRIX_CMD[@]}"
}

preflight
init_meta
echo "latency matrix randread-4k-qd1-latency/randread-4k-qd64-latency: warmup=${WARMUP}s measured=${DURATION}s reps=${REPS} qds='${QDS}'"
echo "engines='${ENGINES}' fio_variant=${FIO_VARIANT} cpu=${MATRIX_CPU} started=$(now_utc)"
for rep in $(seq 1 "$REPS"); do
    for qd in $QDS; do
        for engine in $ENGINES; do
            run_one "$engine" "$qd" "$rep" ||
                echo "warn: cell failed and was recorded (engine=$engine qd=$qd rep=$rep)" >&2
        done
    done
done
echo "latency matrix done: $(now_utc)"
