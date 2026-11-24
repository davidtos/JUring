#!/usr/bin/env bash
#
# bench/read-write/run-fio-brackets.sh -- fio configuration brackets at 4 KiB random read.
#
# Purpose: answer "which fio configuration is the fair reference, and does fio
# match JUring when given the same features?".
# Every bracket is a *disclosed* configuration; only the "matched" variant is a
# read/write reference cell (run-read-matrix.sh owns it).
#
#   default   io_uring, no file/buffer registration, iodepth_batch_submit=1
#             (this is the config the old README quoted as "fio default")
#   batch8/16/32  iodepth_batch_submit sweep (matches JUring's fused submits)
#   sqpoll    --sqthread_poll=1 (kernel-side submission bracket; may be denied
#             without privileges -- recorded as a failed cell, not silently dropped)
#   libaio    libaio engine, non-uring kernel reference
#
# Protocol: 10 s warmup + 30 s measured, R=3, interleaved by rep, CPU 2.
# Cells: QD64 for every bracket (+ QD128 for default/libaio).
#
# Env overrides: WARMUP DURATION REPS QD MATRIX_RESULTS MATRIX_CPU
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=bench/read-write/lib.sh
source "$HERE/lib.sh"
cd "$MATRIX_REPO_ROOT"

WARMUP=${WARMUP:-10}
DURATION=${DURATION:-30}
REPS=${REPS:-3}
QD=${QD:-64}

# variant -> dataset of (engine, fio-variant, qd) tuples; bash 4 associative arrays
declare -A BRACKETS=(
    [default]="fio-io_uring|default|64 fio-io_uring|default|128"
    [batch8]="fio-io_uring|batch8|64"
    [batch16]="fio-io_uring|batch16|64"
    [batch32]="fio-io_uring|batch32|64"
    [sqpoll]="fio-io_uring|sqpoll|64"
    [libaio]="fio-libaio|std|64 fio-libaio|std|128"
)

case_for_qd() {
    # canonical cell slug from bench/analysis/cells.py (cell_for in bench/cells.sh)
    cell_for throughput randread 4k "$1" 2>/dev/null || echo "unknown-qd$1"
}

run_one() {
    local engine=$1 variant=$2 qd=$3 rep=$4
    local case_id
    case_id=$(case_for_qd "$qd")
    local id="${case_id}__${engine}__${variant}__bs4k__qd${qd}__t1__r${rep}"
    local json="$MATRIX_RESULTS/cells/$id.json" log="$MATRIX_RESULTS/logs/$id.log"
    build_engine_cmd "$engine" randread 4k "$qd" "$DURATION" "$WARMUP" "$id" "$json" "$variant"
    run_cell "$case_id" "$engine" "$variant" 4k "$qd" "$rep" "$log" "$json" -- "${MATRIX_CMD[@]}"
}

preflight
init_meta
echo "fio brackets: warmup=${WARMUP}s measured=${DURATION}s reps=${REPS} cpu=${MATRIX_CPU} started=$(now_utc)"
for rep in $(seq 1 "$REPS"); do
    for bracket in default batch8 batch16 batch32 sqpoll libaio; do
        for spec in ${BRACKETS[$bracket]}; do
            IFS='|' read -r engine variant qd <<<"$spec"
            run_one "$engine" "$variant" "$qd" "$rep" ||
                echo "warn: bracket cell failed and was recorded ($bracket qd=$qd rep=$rep)" >&2
        done
    done
done
echo "fio brackets done: $(now_utc)"
