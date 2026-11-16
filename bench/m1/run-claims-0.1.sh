#!/usr/bin/env bash
#
# bench/m1/run-claims-0.1.sh -- reproduce W3/W4 for the 0.1.0 claims pass with a
# batching-and-registration-matched fio reference and rotated engine order.
#
# This addresses the audit finding that the historical read matrix compared JUring
# (fused submit, registered files/buffers) against fio with registration but default
# submit batching, and always ran engines in the same order. It runs two matrices
# into the same session directory (cell ids include the fio variant):
#   1. fio "matched"       registered files + fixed buffers (historical reference)
#   2. fio "matchedbatch"  registered files + fixed buffers + iodepth_batch_submit=qd
# with ROTATE_ENGINES=1 so each repetition rotates juring -> seastar -> fio.
#
# Env overrides: REPS DURATION WARMUP M1_RESULTS M1_CPU
#
# Run ALONE on the machine (preflight rejects foreign CPU load); the session takes
# roughly 1.5 h for R=5 at QD64/QD128 with three engines.
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$HERE/../.."

SESSION=${M1_RESULTS:-$PWD/bench/results/claims-0.1-$(date -u +%Y%m%d)}
export M1_RESULTS=$SESSION
export REPS=${REPS:-5} DURATION=${DURATION:-30} WARMUP=${WARMUP:-10}
export QDS="64 128" ENGINES="juring seastar fio-io_uring" ROTATE_ENGINES=1

echo "session: $M1_RESULTS"
for variant in matched matchedbatch; do
    echo "=== fio variant: $variant ==="
    FIO_VARIANT=$variant "$HERE/run-read-matrix.sh"
done
echo "done: $M1_RESULTS"
echo "analyze with: python3 bench/m1/analyze_m1.py --dir $M1_RESULTS --out-prefix $M1_RESULTS/aggregate/claims"
