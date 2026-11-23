#!/usr/bin/env bash
# bench/write/run-write-3way.sh -- pre-registered 3-way write rerun.
#
# Protocol: docs/write-state-3way-protocol.md (frozen before the first run).
# Frozen lineage: bench/stepd/run-write-chain.sh W2 gate + W1-A "continuous" variant.
# Deviations, all pre-registered: 60/60 windows (bench/write/README.md allows the
# uniform 60/60 override), Seastar added as a measured arm (write path smoke-verified
# in scratch/audit/seastar-audit.md), engine order rotated per generation.
#
# Uses: run-write-cells.sh (generation), analyze_write_state.py (frozen gate),
# analyze_write_3way.py (3-way table). All writes go below bench/results/.
#
# Usage: bash scratch/audit/run-write-rerun-3way.sh [--dry-run]
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."

DRY=0
[[ ${1:-} == --dry-run ]] && DRY=1

MAX_GENS=${RERUN_MAX_GENS:-5}
MIN_VALID=${RERUN_MIN_VALID:-4}      # frozen gate threshold; stop early when reached
BUDGET=${RERUN_BUDGET:-5650}         # wall seconds available for generation starts
GEN_TIMEOUT=${RERUN_GEN_TIMEOUT:-1500}
# A 5th attempt is only started when the first four left us below the frozen
# minimum (i.e. exactly one invalid generation): that is the only case where it
# can still change the verdict.
GEN_MIN_REM=${RERUN_GEN_MIN_REM:-1100}
WRC=bench/write/run-write-cells.sh
ANALYZER=bench/stepd/analyze_write_state.py
ANALYZER3=bench/write/analyze_write_3way.py
WRITE_FILE=${WRITE_FILE:-nvme-data/write_files/single_file_bench_w.bin}
DEVICE_STAT=${DEVICE_STAT:-/sys/block/nvme1n1/stat}
ALLOW_ANY_PATH=${ALLOW_ANY_PATH:-0}
WRITE_ARGS=(--write-file "$WRITE_FILE" --device-stat "$DEVICE_STAT")
if (( ALLOW_ANY_PATH )); then WRITE_ARGS+=(--allow-any-path); fi
READ_FILE=/mnt/TestData/read_files/single_file_bench.bin
# 3-cycle rotation: first/last arm changes between generations.
ORDERS=(
    "juring fio seastar"
    "seastar juring fio"
    "fio seastar juring"
    "juring fio seastar"
    "seastar juring fio"
)

if (( DRY )); then
    S=${RERUN_SESSION:-bench/results/dryrun-write-3way}
    echo "# DRY-RUN: no measurement, no directories, no device I/O"
else
    S=bench/results/write-3way-$(date -u +%Y%m%d-%H%M%S)
    mkdir -p -- "$S"
    dirty=false
    [[ -n "$(git status --porcelain 2>/dev/null | head -n1)" ]] && dirty=true
    {
        echo "# Pre-registered state-controlled write rerun, 3-way (continuous, 60/60)"
        echo "# protocol: docs/write-state-3way-protocol.md"
        echo "started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "session=$S"
        echo "host=$(hostname) kernel=$(uname -r) user=$(id -un)"
        echo "git_head=$(git rev-parse HEAD 2>/dev/null || echo unknown) git_dirty=$dirty"
        echo "protocol_sha256=$(sha256sum docs/write-state-3way-protocol.md | awk '{print $1}')"
        echo "harness_sha256:"
        sha256sum "$WRC" bench/write/precondition.sh bench/write/sample-device-iops.sh \
            "$ANALYZER" "$ANALYZER3" bench/juring/run-juring.sh 2>/dev/null | sed 's/^/  /' || true
        echo "write_file=$(stat -c 'size=%s blocks=%b mtime=%y' -- "$WRITE_FILE" 2>/dev/null || echo missing)"
        echo "device_stat=$DEVICE_STAT allow_any_path=${ALLOW_ANY_PATH:-0}"
        echo "read_file=$(stat -c 'size=%s mtime=%y' -- "$READ_FILE" 2>/dev/null || echo missing)"
        echo "governor=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)"
        echo "io_uring_disabled=$(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo unknown)"
        echo "loadavg_start=$(cat /proc/loadavg)"
        echo "thermal_start_mc=$(cat /sys/class/nvme/nvme1/hwmon*/temp1_input 2>/dev/null | head -n1 || echo unavailable)"
        echo "max_gens=$MAX_GENS min_valid=$MIN_VALID budget_s=$BUDGET gen_timeout_s=$GEN_TIMEOUT gen_min_remaining_s=$GEN_MIN_REM"
        echo "cells=W7,W8 arms=juring,fio-io_uring[matched,matchedbatch],seastar reps=1/gen warmup_s=60 measured_s=60"
        echo "precondition=session-once precond_rest_s=0 rest_s=0 rest_scope=runs seed=20261113 cpu=2"
        echo "orders=${ORDERS[*]}"
    } >"$S/PROTOCOL.txt"
    cat -- "$S/PROTOCOL.txt"

    la=$(awk '{print $1}' /proc/loadavg)
    awk -v x="$la" 'BEGIN{exit !(x < 1.6)}' || {
        echo "ABORT: loadavg(1m)=$la >= 1.6; machine not idle" >&2; exit 1; }
    if pgrep -f 'seastar_file_bench|JUringFileBench|fio .*single_file_bench' >/dev/null 2>&1; then
        echo "ABORT: another benchmark process is running; read session must finish first" >&2; exit 1
    fi
    [[ -f $WRITE_FILE ]] || { echo "ABORT: missing write file $WRITE_FILE" >&2; exit 1; }
    [[ $(stat -c%s -- "$WRITE_FILE") == 10737418240 ]] || { echo "ABORT: write file is not 10 GiB" >&2; exit 1; }
    [[ $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null) == performance ]] || {
        echo "ABORT: governor is not performance" >&2; exit 1; }
fi

T0=$(date +%s)
GENS=""
VALID=""
NVALID=0
for g in $(seq 1 "$MAX_GENS"); do
    elapsed=$(( $(date +%s) - T0 ))
    rem=$(( BUDGET - elapsed ))
    if (( rem < GEN_MIN_REM )); then
        [[ -n ${VALID} ]] && echo "TIMEBOX: ${rem}s left < ${GEN_MIN_REM}s needed; stopping before g$g"
        break
    fi
    order=${ORDERS[$(( (g - 1) % ${#ORDERS[@]} ))]}
    echo "== generation g$g order='$order' elapsed=${elapsed}s remaining=${rem}s =="

    if (( DRY )); then
        bash "$WRC" \
            --cells W7,W8 --engines "$order" --allow-seastar-write \
            --fio-variants matched,matchedbatch --no-w7-sweep \
            --reps-by-cell W7=1,W8=1 \
            --precondition session-once --precond-rest-sec 0 \
            --rest-sec 0 --rest-scope runs \
            --warmup-sec 60 --measured-sec 60 \
            --seed 20261113 --cpu 2 "${WRITE_ARGS[@]}" \
            --session-dir "$S/g$g" \
            --loadavg-max 1.6 --loadavg-action abort \
            --dry-run
        echo "# DRY-RUN: real run would then classify each generation with:"
        echo "#   python3 $ANALYZER --gen-dir $S/g$g      # PILOT_PASS 0/1 (frozen gate)"
        echo "#   python3 $ANALYZER3 --session $S --gens $GENS --out $S/analysis-3way"
        echo "DRY_RUN_OK session=$S max_gens=$MAX_GENS plan_runs_per_gen=8 est_sec_per_gen=1114"
        exit 0
    fi

    rc=0
    timeout -k 30 "$GEN_TIMEOUT" bash "$WRC" \
        --cells W7,W8 --engines "$order" --allow-seastar-write \
        --fio-variants matched,matchedbatch --no-w7-sweep \
        --reps-by-cell W7=1,W8=1 \
        --precondition session-once --precond-rest-sec 0 \
        --rest-sec 0 --rest-scope runs \
        --warmup-sec 60 --measured-sec 60 \
        --seed 20261113 --cpu 2 "${WRITE_ARGS[@]}" \
        --session-dir "$S/g$g" \
        --loadavg-max 1.6 --loadavg-action abort || rc=$?
    if (( rc == 124 )); then
        echo "GENERATION g$g TIMEOUT (${GEN_TIMEOUT}s cap) -- infrastructure failure, stopping"
        break
    fi
    if [[ ! -f $S/g$g/cell-meta.tsv ]]; then
        echo "GENERATION g$g produced no cell-meta.tsv (rc=$rc) -- infrastructure failure, stopping"
        break
    fi
    (( rc == 0 )) || echo "WARN: generation g$g harness rc=$rc (a run failed; classified invalid)"
    GENS="${GENS:+$GENS,}g$g"
    classify=$(python3 "$ANALYZER" --gen-dir "$S/g$g" | tee "$S/g$g.classify.txt")
    pass=$(awk '/^PILOT_PASS/{print $2}' <<<"$classify" | tail -n1)
    [[ -z $pass ]] && pass=0
    echo "GENERATION g$g PILOT_PASS=$pass"
    if (( pass == 1 )); then
        VALID="${VALID:+$VALID,}g$g"
        NVALID=$((NVALID + 1))
    fi
    if (( NVALID >= MIN_VALID )); then
        echo "STOP: reached $NVALID valid generation(s) (frozen minimum $MIN_VALID)"
        break
    fi
done

if (( DRY )); then exit 0; fi

if [[ -z $GENS ]]; then
    echo "NO_GENERATION_COMPLETED: infrastructure failure; nothing to analyze" >&2
    exit 2
fi

echo "== frozen gate analysis =="
python3 "$ANALYZER" --session "$S" --gens "$GENS" --out "$S/analysis" | tee "$S/analysis.log"
cat -- "$S/analysis/write-state-summary.md"

echo "== 3-way table =="
python3 "$ANALYZER3" --session "$S" --gens "$GENS" --out "$S/analysis-3way" | tee "$S/analysis-3way.log"

echo "SESSION_END session=$S gens=$GENS valid=$VALID elapsed_s=$(( $(date +%s) - T0 ))"
