#!/usr/bin/env bash
#
# bench/m1/run-read-session.sh -- the complete M1 read-path session, in order.
#
# Phases (docs/benchmark-methodology.md §2 Phase A + the new cross-check):
#   1. W1-W4   4K randread throughput, three-way, 10 s warmup + 30 s, R=5
#   2. fio configuration brackets (matched/default/batch/sqpoll/libaio)
#   3. W15/W16 latency, three-way, 10 s warmup + 120 s, R=5
#   4. perf cells: cycles/instructions/syscalls per op, QD1+QD64, R=3,
#      fio comparator in both default and registration-matched configurations
#   5. W9-W11 1 MiB sequential read, three-way, 10 s warmup + 30 s, R=5
#   6. io_tester cross-check -- writes 2x10 GiB setup fills on nvme-data, so it
#      runs LAST, after every read cell (the read dataset lives on the same NVMe)
#
# Between phases: 30 s settle. Each phase's preflight relaxes only the *residual
# loadavg* check (our own finished benchmark leaves D-state tasks in the 1 min
# average); the foreign-process >20 % CPU check stays strict.
#
# Everything is appended to bench/results/m1-2026-11-13/logs/session.log;
# per-cell output goes to cells/, logs/, perf/, iotester/ as described in lib.sh.
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=bench/m1/lib.sh
source "$HERE/lib.sh"
cd "$M1_REPO_ROOT"

SESSION_LOG=$M1_RESULTS/logs/session.log
mkdir -p "$M1_RESULTS/logs"

phase() { echo "[$(now_utc)] === $* ===" | tee -a "$SESSION_LOG"; }

run_phase() {
    local name=$1
    shift
    phase "phase start: $name"
    local rc=0
    if ALLOW_RESIDUAL_LOAD=1 "$@" >>"$SESSION_LOG" 2>&1; then
        phase "phase ok: $name"
    else
        rc=$?
        phase "phase FAILED: $name (exit $rc)"
        return "$rc"
    fi
    phase "settle 30 s"
    sleep 30
}

"$HERE/write-manifest.sh" | tee -a "$SESSION_LOG"
phase "session start"
run_phase read-matrix "$HERE/run-read-matrix.sh"
run_phase fio-brackets "$HERE/run-fio-brackets.sh"
run_phase latency-matrix "$HERE/run-latency-matrix.sh"
run_phase perf-cells "$HERE/run-perf-cells.sh"
run_phase seqread-matrix "$HERE/run-seqread-matrix.sh"
run_phase iotester-check env DURATION=30 REPS=3 "$HERE/run-iotester-check.sh"
phase "session done: $(now_utc)"
