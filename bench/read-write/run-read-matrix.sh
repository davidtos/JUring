#!/usr/bin/env bash
#
# bench/read-write/run-read-matrix.sh -- 4 KiB random read throughput (QD 1/16/64/128), three-way.
#
# Protocol (docs/benchmark-methodology.md): 10 s warmup + 30 s measured
# per cell, R=5, one fresh process per rep, every engine pinned to CPU 2.
# Engine order is interleaved per rep so host-state drift is common-mode (the
# rotated engine order).
# Set ROTATE_ENGINES=1 to rotate the order by (rep-1) instead of using the fixed
# ENGINES order; the 0.1.0 claims rerun uses rotation (a fixed order can bias a
# persistent first-engine advantage).
#
# Engine configurations (each engine's best documented configuration, fully
# disclosed):
#   juring          registered files + qd fixed buffers, SINGLE_ISSUER|COOP_TASKRUN|
#                   DEFER_TASKRUN (via bench/juring/run-juring.sh)
#   seastar         default io_uring reactor backend, --smp 1 --cpuset 2
#   fio-io_uring    io_uring, default batching, with --registerfiles=1 --fixedbufs=1
#                   ("matched" = matches JUring's registration; see run-fio-brackets.sh
#                   for the unmatched/registration-free bracket)
#
# Env overrides: WARMUP DURATION REPS QDS ENGINES FIO_VARIANT ROTATE_ENGINES MATRIX_RESULTS MATRIX_CPU
#
# Output: $MATRIX_RESULTS/{cells,logs}/<id>.json|.log + cell-meta.tsv
# (MATRIX_RESULTS defaults to bench/results/read-write).
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=bench/read-write/lib.sh
source "$HERE/lib.sh"
cd "$MATRIX_REPO_ROOT"

WARMUP=${WARMUP:-10}
DURATION=${DURATION:-30}
REPS=${REPS:-5}
QDS=${QDS:-"1 16 64 128"}
ENGINES=${ENGINES:-"juring seastar fio-io_uring"}
FIO_VARIANT=${FIO_VARIANT:-matched}
ROTATE_ENGINES=${ROTATE_ENGINES:-0}

case_for_qd() {
    # canonical cell slug from bench/analysis/cells.py (cell_for in bench/cells.sh)
    cell_for throughput randread 4k "$1" 2>/dev/null || echo "unknown-qd$1"
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
echo "read matrix (4 KiB random read QD 1/16/64/128): warmup=${WARMUP}s measured=${DURATION}s reps=${REPS} qds='${QDS}'"
echo "engines='${ENGINES}' fio_variant=${FIO_VARIANT} cpu=${MATRIX_CPU} started=$(now_utc)"
read -r -a ENGINE_ARRAY <<< "$ENGINES"
ENGINE_COUNT=${#ENGINE_ARRAY[@]}
for rep in $(seq 1 "$REPS"); do
    for qd in $QDS; do
        if [[ $ROTATE_ENGINES == 1 && $rep -gt 1 ]]; then
            shift_by=$(( (rep - 1) % ENGINE_COUNT ))
            order=()
            for ((k = 0; k < ENGINE_COUNT; k++)); do
                order+=("${ENGINE_ARRAY[$(((k + shift_by) % ENGINE_COUNT))]}")
            done
        else
            order=("${ENGINE_ARRAY[@]}")
        fi
        for engine in "${order[@]}"; do
            run_one "$engine" "$qd" "$rep" ||
                echo "warn: cell failed and was recorded (engine=$engine qd=$qd rep=$rep)" >&2
        done
    done
done
echo "read matrix done: $(now_utc)"
