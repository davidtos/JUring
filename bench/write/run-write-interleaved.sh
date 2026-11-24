#!/usr/bin/env bash
# bench/write/run-write-interleaved.sh -- interleaved write bracket (fallback).
#
# Protocol: fixed options in this runner; see BENCHMARK.md, "The 2026-11-23 write rerun".
# Why: the primary state-controlled 3-way campaign failed its homogeneity gate
# in both completed passes on this drive, so this bracket alternates the
# engines in short cycles instead of claiming a single homogeneous state.
# Expected wall time: ~24 min for 2 cells x 5 cycles x 3 engines.
#
# Usage: bash bench/write/run-write-interleaved.sh [--dry-run]
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."

REPS=${INTERLEAVED_REPS:-5}
WARMUP=${INTERLEAVED_WARMUP:-15}
MEASURED=${INTERLEAVED_MEASURED:-30}
WRC=bench/write/run-write-cells.sh
ANALYZER=bench/write/analyze_write_interleaved.py
WRITE_FILE=${WRITE_FILE:-nvme-data/write_files/single_file_bench_w.bin}
DEVICE_STAT=${DEVICE_STAT:-/sys/block/nvme1n1/stat}
ALLOW_ANY_PATH=${ALLOW_ANY_PATH:-0}
WRITE_ARGS=(--write-file "$WRITE_FILE" --device-stat "$DEVICE_STAT")
if (( ALLOW_ANY_PATH )); then WRITE_ARGS+=(--allow-any-path); fi
SESSION_PREFIX=bench/results/write-interleaved
DRY_RUN=0

usage() { sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }
case "${1:-}" in
    --dry-run) DRY_RUN=1 ;;
    -h | --help) usage; exit 0 ;;
    "") ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
esac

run_harness() {
    # "$@" is either --dry-run or nothing
    timeout -k 30 2400 bash "$WRC" \
        --cells randwrite-4k-qd64,randwrite-4k-qd128 --engines "juring fio seastar" --allow-seastar-write \
        --fio-variants matched --no-batching-sweep --reps "$REPS" \
        --warmup-sec "$WARMUP" --measured-sec "$MEASURED" \
        --precondition none --rest-sec 0 --rest-scope runs \
        --seed 20261113 --cpu 2 "${WRITE_ARGS[@]}" \
        --session-dir "$S" --loadavg-max 1.6 --loadavg-action abort "$@"
}

if (( DRY_RUN )); then
    S=${INTERLEAVED_SESSION:-$SESSION_PREFIX-dryrun}
    echo "# DRY-RUN: plan only, no directories, no I/O, no sleeps"
    run_harness --dry-run
    echo "DRY_RUN_OK session=$S runs_per_cell=$(( REPS * 3 )) cells=2"
    exit 0
fi

S=$SESSION_PREFIX-$(date -u +%Y%m%d-%H%M%S)
mkdir -p -- "$S"
dirty=false
[[ -n "$(git status --porcelain 2>/dev/null | head -n1)" ]] && dirty=true
{
    echo "# Interleaved write bracket (fallback), protocol fixed before the run"
    echo "# protocol: fixed options in this runner (BENCHMARK.md, 'The 2026-11-23 write rerun')"
    echo "started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "session=$S"
    echo "host=$(hostname) kernel=$(uname -r) user=$(id -un)"
    echo "git_head=$(git rev-parse HEAD 2>/dev/null || echo unknown) git_dirty=$dirty"
    echo "harness_sha256:"
    sha256sum "$WRC" bench/write/precondition.sh bench/write/sample-device-iops.sh \
        "$ANALYZER" bench/write/run-write-interleaved.sh \
        bench/juring/run-juring.sh 2>/dev/null | sed 's/^/  /' || true
    echo "write_file=$(stat -c 'size=%s blocks=%b mtime=%y' -- "$WRITE_FILE" 2>/dev/null || echo missing)"
    echo "device_stat=$DEVICE_STAT allow_any_path=${ALLOW_ANY_PATH:-0}"
    echo "governor=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)"
    echo "io_uring_disabled=$(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo unknown)"
    echo "loadavg_start=$(cat /proc/loadavg)"
    echo "thermal_start_mc=$(cat /sys/class/nvme/nvme1/hwmon*/temp1_input 2>/dev/null | head -n1 || echo unavailable)"
    echo "cells=randwrite-4k-qd64,randwrite-4k-qd128 engines=juring,fio-io_uring[matched],seastar cycles=$REPS warmup_s=$WARMUP measured_s=$MEASURED"
    echo "precondition=none rest_s=0 rest_scope=runs seed=20261113 cpu=2"
} >"$S/PROTOCOL.txt"
cat -- "$S/PROTOCOL.txt"

la=$(awk '{print $1}' /proc/loadavg)
awk -v x="$la" 'BEGIN{exit !(x < 1.6)}' || {
    echo "ABORT: loadavg(1m)=$la >= 1.6; machine not idle" >&2; exit 1; }
if pgrep -f 'seastar_file_bench|JUringFileBench|fio .*single_file_bench' >/dev/null 2>&1; then
    echo "ABORT: another benchmark process is running" >&2; exit 1
fi
[[ -f $WRITE_FILE ]] || { echo "ABORT: missing write file $WRITE_FILE" >&2; exit 1; }
[[ $(stat -c%s -- "$WRITE_FILE") == 10737418240 ]] || { echo "ABORT: write file is not 10 GiB" >&2; exit 1; }
[[ $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null) == performance ]] || {
    echo "ABORT: governor is not performance" >&2; exit 1; }

rc=0
run_harness || rc=$?
if (( rc == 124 )); then
    echo "BRACKET TIMEOUT -- infrastructure failure" >&2; exit 2
fi
(( rc == 0 )) || echo "WARN: harness rc=$rc; analyzing whatever completed" >&2

python3 "$ANALYZER" --session "$S" --out "$S/analysis" | tee "$S/analysis.log"
echo "SESSION_END session=$S"
