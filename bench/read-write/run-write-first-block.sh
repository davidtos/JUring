#!/usr/bin/env bash
#
# bench/read-write/run-write-first-block.sh -- the first write block kickoff.
#
# 1. create the dedicated 10 GiB non-sparse O_DIRECT write file on nvme-data/
#    (the data NVMe, now writable; the read dataset is never touched),
# 2. validate seastar's randwrite path (previously untested) with a
#    short cell and a hard assertion (errors == 0, iops > 0),
# 3. run the first measured write cell: randwrite-4k-qd64 (4K randwrite QD64) for juring,
#    fio (default + registration-matched) and seastar, R=3, with the
#    preconditioning burst once per session and the between-run rest between
#    rep groups.
#
# This is deliberately a TRIMMED block, not the full write matrix
# (78 runs / ~11 h, see bench/write/README.md). Deviations from the protocol in
# docs/benchmark-methodology.md:
#   - only the 4 KiB random-write QD64 cell is measured; the other write cells are pending,
#   - preconditioning is session-once (the preconditioning burst allows per-block),
#     not per-cell,
#   - rests are between rep groups (--rest-scope reps), not after every run.
# Both deviations are disclosed in the session's notes.md.
#
# Artifacts: $SESSION/{cells,logs,series,state}/ plus the write-block.log
# (SESSION defaults to bench/results/write-matrix).
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$HERE/../.." && pwd)
cd "$REPO_ROOT"

SESSION=${SESSION_DIR:-$REPO_ROOT/bench/results/write-matrix}
LOG=$SESSION/logs/write-block.log
WR_FILE=$REPO_ROOT/nvme-data/write_files/single_file_bench_w.bin
SYSROOT=$REPO_ROOT/.sysroot/root/usr
mkdir -p "$SESSION/logs"

phase() { echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] === $* ===" | tee -a "$LOG"; }

phase "preflight: idle gate"
gov=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)
echo "governor: $gov" | tee -a "$LOG"
[[ $gov == performance ]] || { echo "error: governor != performance" | tee -a "$LOG"; exit 1; }
# Wait (up to 10 min) for the residual loadavg of the finished read session to
# decay below the background-load gate (loadavg(1m) > 1.6 aborts/flags the run).
# After that, the per-run gate runs in "warn" mode
# because our own write runs legitimately raise loadavg (D-state write tasks);
# every cell records its gate value either way.
for i in $(seq 1 60); do
    la1=$(awk '{print $1}' /proc/loadavg)
    if awk -v x="$la1" 'BEGIN{exit !(x < 0.5)}'; then
        echo "loadavg(1m)=$la1 < 0.5: starting" | tee -a "$LOG"
        break
    fi
    echo "waiting for idle: loadavg(1m)=$la1" | tee -a "$LOG"
    sleep 10
done

phase "1/3 create write file"
"$REPO_ROOT/bench/write/create-write-file.sh" 2>&1 | tee -a "$LOG" | tail -5

phase "2/3 validate seastar randwrite path (short cell)"
SMOKE_JSON=$SESSION/logs/smoke_seastar_randwrite.json
LD_LIBRARY_PATH="$SYSROOT/lib64" taskset -c 2 \
    "$REPO_ROOT/bench/seastar/seastar_file_bench" \
    --workload randwrite --file "$WR_FILE" --bs 4k --qd 64 --seconds 5 --warmup-sec 2 \
    --seed 20261113 --label smoke_seastar_randwrite --json "$SMOKE_JSON" \
    --smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend io_uring \
    >>"$LOG" 2>&1
python3 - "$SMOKE_JSON" <<'PY' | tee -a "$LOG"
import json
import sys

d = json.load(open(sys.argv[1]))
r = d["runs"][0]
print(f"seastar randwrite validation: iops={r['iops']:.0f} errors={r['errors_total']} "
      f"bytes={r['bytes_total']} p99_us={r['latency_us']['p99']:.1f}")
if r["errors_total"] != 0 or r["iops"] <= 0 or r["bytes_total"] <= 0:
    raise SystemExit("FAIL: seastar randwrite path is not usable")
print("seastar randwrite path: VALID (proceeding to the measured block)")
PY

phase "3/3 randwrite-4k-qd64 measured block (juring, fio default+matched, seastar; R=3)"
"$REPO_ROOT/bench/write/run-write-cells.sh" \
    --cells randwrite-4k-qd64 --reps 3 --precondition session-once --rest-scope reps \
    --engines juring,fio,seastar --fio-variants default,matched \
    --no-batching-sweep --allow-seastar-write --loadavg-action warn 2>&1 | tee -a "$LOG"

phase "write block done"
