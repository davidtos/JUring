#!/usr/bin/env bash
#
# bench/read-write/run-seqread-matrix.sh -- 1 MiB sequential read (QD 1/8/32), three-way.
#
# Protocol: 10 s warmup + 30 s measured per cell, R=5, one fresh process per rep,
# pinned to CPU 2, engines interleaved by rep. Same 10 GiB file as the 4 KiB random-read matrix (the
# sequential stream wraps at EOF, so no dataset reset is needed).
#
# Cells: seqread-1m-qd1 = QD1, seqread-1m-qd8 = QD8, seqread-1m-qd32 = QD32 (docs/benchmark-methodology.md).
# fio reference configuration: io_uring with registration matched to what JUring
# can actually use at each QD (RLIMIT_MEMLOCK=8 MiB here):
#   QD1  -> --registerfiles=1 --fixedbufs=1  (1 MiB of fixed buffers fits)
#   QD8  -> --registerfiles=1                (8 MiB is exactly the memlock cap;
#   QD32 -> --registerfiles=1                 fio's fixedbufs registration fails
#                                            with ENOMEM, and JUring also falls
#                                            back to unregistered arena buffers)
# The per-cell JSON io_buffer_mode records JUring's actual buffer mode.
#
# Note: at 1 MiB the JUring harness may fall back to unregistered arena buffers
# when qd*1MiB exceeds RLIMIT_MEMLOCK (8 MiB here); the JSON io_buffer_mode field
# records which path was used per cell.
#
# Env overrides: WARMUP DURATION REPS QDS ENGINES FIO_VARIANT MATRIX_RESULTS MATRIX_CPU
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=bench/read-write/lib.sh
source "$HERE/lib.sh"
cd "$MATRIX_REPO_ROOT"

WARMUP=${WARMUP:-10}
DURATION=${DURATION:-30}
REPS=${REPS:-5}
QDS=${QDS:-"1 8 32"}
ENGINES=${ENGINES:-"juring seastar fio-io_uring"}
FIO_VARIANT=${FIO_VARIANT:-auto}

fio_variant_for_qd() {
    if [[ $FIO_VARIANT != auto ]]; then echo "$FIO_VARIANT"; return; fi
    case $1 in
        1) echo matched ;;
        *) echo regfile ;;
    esac
}

case_for_qd() {
    # canonical cell slug from bench/analysis/cells.py (cell_for in bench/cells.sh)
    cell_for throughput seqread 1m "$1" 2>/dev/null || echo "unknown-qd$1"
}

run_one() {
    local engine=$1 qd=$2 rep=$3
    local case_id variant=std
    case_id=$(case_for_qd "$qd")
    if [[ $engine == fio-* ]]; then variant=$(fio_variant_for_qd "$qd"); fi
    local id="${case_id}__${engine}__${variant}__bs1m__qd${qd}__t1__r${rep}"
    local json="$MATRIX_RESULTS/cells/$id.json" log="$MATRIX_RESULTS/logs/$id.log"
    build_engine_cmd "$engine" seqread 1m "$qd" "$DURATION" "$WARMUP" "$id" "$json" "$variant"
    run_cell "$case_id" "$engine" "$variant" 1m "$qd" "$rep" "$log" "$json" -- "${MATRIX_CMD[@]}"
}

preflight
init_meta
echo "seqread matrix (1 MiB sequential read QD 1/8/32): warmup=${WARMUP}s measured=${DURATION}s reps=${REPS} qds='${QDS}'"
echo "engines='${ENGINES}' fio_variant=${FIO_VARIANT} (auto: qd1=matched, qd8/32=regfile) cpu=${MATRIX_CPU} started=$(now_utc)"
for rep in $(seq 1 "$REPS"); do
    for qd in $QDS; do
        for engine in $ENGINES; do
            run_one "$engine" "$qd" "$rep" ||
                echo "warn: cell failed and was recorded (engine=$engine qd=$qd rep=$rep)" >&2
        done
    done
done
echo "seqread matrix done: $(now_utc)"
