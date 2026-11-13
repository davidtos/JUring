#!/usr/bin/env bash
#
# bench/m1/run-read-matrix.sh -- W1-W4: 4 KiB random read throughput, three-way.
#
# Protocol (docs/benchmark-methodology.md §2/§4/§7): 10 s warmup + 30 s measured
# per cell, R=5, one fresh process per rep, every engine pinned to CPU 2.
# Engine order is interleaved per rep (juring, seastar, fio) so host-state drift
# is common-mode (D6).
#
# Engine configurations (F0: best documented configuration for each):
#   juring          registered files + qd fixed buffers, SINGLE_ISSUER|COOP_TASKRUN|
#                   DEFER_TASKRUN (via bench/juring/run-juring.sh)
#   seastar         default io_uring reactor backend, --smp 1 --cpuset 2
#   fio-io_uring    io_uring, default batching, with --registerfiles=1 --fixedbufs=1
#                   ("matched" = matches JUring's registration; see run-fio-brackets.sh
#                   for the unmatched/registration-free bracket)
#
# Env overrides: WARMUP DURATION REPS QDS ENGINES FIO_VARIANT M1_RESULTS M1_CPU
#
# Output: bench/results/m1-2026-11-13/{cells,logs}/<id>.json|.log + cell-meta.tsv
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=bench/m1/lib.sh
source "$HERE/lib.sh"
cd "$M1_REPO_ROOT"

WARMUP=${WARMUP:-10}
DURATION=${DURATION:-30}
REPS=${REPS:-5}
QDS=${QDS:-"1 16 64 128"}
ENGINES=${ENGINES:-"juring seastar fio-io_uring"}
FIO_VARIANT=${FIO_VARIANT:-matched}

case_for_qd() {
    case $1 in
        1) echo W1 ;;
        16) echo W2 ;;
        64) echo W3 ;;
        128) echo W4 ;;
        *) echo "W?qd$1" ;;
    esac
}

run_one() {
    local engine=$1 qd=$2 rep=$3
    local case_id variant=std
    case_id=$(case_for_qd "$qd")
    if [[ $engine == fio-* ]]; then variant=$FIO_VARIANT; fi
    local id="${case_id}__${engine}__${variant}__bs4k__qd${qd}__t1__r${rep}"
    local json="$M1_RESULTS/cells/$id.json" log="$M1_RESULTS/logs/$id.log"
    build_engine_cmd "$engine" randread 4k "$qd" "$DURATION" "$WARMUP" "$id" "$json" "$variant"
    run_cell "$case_id" "$engine" "$variant" 4k "$qd" "$rep" "$log" "$json" -- "${M1_CMD[@]}"
}

preflight
init_meta
echo "read matrix W1-W4: warmup=${WARMUP}s measured=${DURATION}s reps=${REPS} qds='${QDS}'"
echo "engines='${ENGINES}' fio_variant=${FIO_VARIANT} cpu=${M1_CPU} started=$(now_utc)"
for rep in $(seq 1 "$REPS"); do
    for qd in $QDS; do
        for engine in $ENGINES; do
            run_one "$engine" "$qd" "$rep" ||
                echo "warn: cell failed and was recorded (engine=$engine qd=$qd rep=$rep)" >&2
        done
    done
done
echo "read matrix done: $(now_utc)"
