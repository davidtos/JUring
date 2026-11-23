#!/usr/bin/env bash
#
# bench/m1/run-claims-0.1.sh -- reproduce W3/W4 for the 0.1.0 claims pass with a
# batching-and-registration-matched fio reference and rotated engine order.
#
# This addresses the audit finding that the historical read matrix compared JUring
# (fused submit, registered files/buffers) against fio with registration but default
# submit batching, and always ran engines in the same order. It runs two matrices:
#   1. read-matrix-matched/fio:       registered files + fixed buffers (historical reference)
#   2. read-matrix-matchedbatch/fio:  registered files + fixed buffers + iodepth_batch_submit=qd
# with ROTATE_ENGINES=1 so each repetition rotates juring -> seastar -> fio.
#
# Each fio variant gets its own sub-session: juring/seastar cell ids do not contain the
# fio variant, so writing both matrices into one directory would overwrite the first
# matrix's raw juring/seastar JSONs (cell-meta.tsv would still hold both runs).
#
# Env overrides: REPS DURATION WARMUP M1_RESULTS M1_CPU
#
# Run ALONE on the machine (preflight rejects foreign CPU load); both matrices take
# roughly 1 h for R=5 at QD64/QD128 with three engines.
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$HERE/../.."

SESSION=${M1_RESULTS:-$PWD/bench/results/claims-0.1-$(date -u +%Y%m%d)}
export REPS=${REPS:-5} DURATION=${DURATION:-30} WARMUP=${WARMUP:-10}
export QDS="64 128" ENGINES="juring seastar fio-io_uring" ROTATE_ENGINES=1

echo "session: $SESSION"
first=1
for variant in matched matchedbatch; do
    sub="$SESSION/read-matrix-$variant"
    echo "=== fio variant: $variant -> $sub ==="
    # The second matrix is a chained phase: the previous matrix's just-finished
    # engine processes can still inflate loadavg(1m) from D state. The strict
    # foreign-process check still runs; only the stale own-load gate is relaxed.
    allow=0
    if ((first == 0)); then allow=1; fi
    ALLOW_RESIDUAL_LOAD=$allow FIO_VARIANT=$variant M1_RESULTS="$sub" "$HERE/run-read-matrix.sh"
    first=0
done
echo "done: $SESSION"
echo "analyze each sub-session with:"
for variant in matched matchedbatch; do
    echo "  python3 bench/m1/analyze_read_write.py --dir $SESSION/read-matrix-$variant --out-prefix $SESSION/read-matrix-$variant/aggregate/claims"
done
