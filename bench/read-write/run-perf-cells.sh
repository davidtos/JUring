#!/usr/bin/env bash
#
# bench/read-write/run-perf-cells.sh -- CPU cycles/instructions + syscalls per op.
#
# Method (docs/benchmark-methodology.md): the engine runs
# 10 s warmup + 40 s measured. At wall-clock warmup+1 s we attach
#   perf stat -p <engine-pid> -e <events> -- sleep 30
# so the counter window is the engine's steady-state phase, after JVM JIT and
# seastar/reactor startup. `perf stat -p PID` counts every thread of the process
# (verified on this host), so JVM worker/GC threads and seastar shards are
# included; kernel io-wq workers are NOT part of the process and are therefore
# excluded for all engines (recorded as a caveat in the report).
#
# ops in the window is estimated from the engine's own average IOPS x window.
# Raw perf output: perf/<id>.txt; compact numbers: perf-cells.tsv.
#
# Env overrides: WARMUP DURATION WINDOW REPS QDS ENGINES PERF_EVENTS MATRIX_RESULTS MATRIX_CPU
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=bench/read-write/lib.sh
source "$HERE/lib.sh"
cd "$MATRIX_REPO_ROOT"

WARMUP=${WARMUP:-10}
DURATION=${DURATION:-40}
WINDOW=${WINDOW:-30}
REPS=${REPS:-3}
QDS=${QDS:-"1 64"}
ENGINES=${ENGINES:-"juring seastar fio-io_uring"}
PERF_EVENTS=${PERF_EVENTS:-"task-clock,cycles:u,cycles:k,instructions:u,instructions:k,raw_syscalls:sys_enter,context-switches"}
PERF_TSV=$MATRIX_RESULTS/perf-cells.tsv
# fio comparator configurations for the CPU gate: default (no registration) and
# matched (--registerfiles=1 --fixedbufs=1, the fair comparison vs JUring).
FIO_PERF_VARIANTS=${FIO_PERF_VARIANTS:-"default matched"}

init_perf_tsv() {
    if [[ ! -s $PERF_TSV ]]; then
        printf 'case\tengine\tvariant\tqd\trep\tattach_s\twindow_s\tops_window\tiops\ttask_clock_s\tcycles_u\tcycles_k\tinstructions_u\tinstructions_k\tsyscalls\tctxt_switches\tcycles_u_per_op\tcycles_k_per_op\tcycles_per_op\tinstructions_per_op\tsyscalls_per_op\tctxt_per_op\n' >"$PERF_TSV"
    fi
}

# collect_pids <pid> -- comma list of the process and all its descendants.
# Needed for fio: with numjobs=1 fio forks the job into a CHILD process, so
# `perf stat -p <parent>` would only see the idle parent (measured 5 ms/3 s).
collect_pids() {
    local root=$1
    local out=$root
    local p k kids
    local queue=("$1")
    while ((${#queue[@]} > 0)); do
        p=${queue[0]}
        queue=("${queue[@]:1}")
        kids=$(ps -eo pid=,ppid= | awk -v pp="$p" '$2 == pp {print $1}')
        for k in $kids; do
            out="$out,$k"
            queue+=("$k")
        done
    done
    echo "$out"
}

case_for_qd() {
    # canonical cell slug from bench/analysis/cells.py (cell_for in bench/cells.sh)
    cell_for throughput randread 4k "$1" 2>/dev/null || echo "unknown-qd$1"
}

run_perf_cell() {
    local engine=$1 qd=$2 rep=$3 fio_var=${4:-default}
    local case_id variant=std attach_s=$((WARMUP + 1))
    case_id=$(case_for_qd "$qd")
    if [[ $engine == fio-* ]]; then
        if [[ $fio_var == default ]]; then variant=perf; else variant="perf-$fio_var"; fi
    fi
    local id="${case_id}__${engine}__perf__bs4k__qd${qd}__t1__r${rep}"
    local json="$MATRIX_RESULTS/cells/$id.json" log="$MATRIX_RESULTS/logs/$id.log"
    local perfout="$MATRIX_RESULTS/perf/$id.txt"
    build_engine_cmd "$engine" randread 4k "$qd" "$DURATION" "$WARMUP" "$id" "$json" "$fio_var"

    local rs0 rsec0 ws0 wsec0 rs1 rsec1 ws1 wsec1 la0 la1
    read -r rs0 rsec0 ws0 wsec0 <<<"$(devstat)"
    la0=$(loadavg)
    local status=ok pid t0 t1 wall
    t0=$(date +%s.%N)
    taskset -c "$MATRIX_CPU" "${MATRIX_CMD[@]}" >"$log" 2>>"$log" &
    pid=$!
    sleep "$attach_s"
    if ! kill -0 "$pid" 2>/dev/null; then
        echo "warn: engine exited before perf attach (engine=$engine qd=$qd rep=$rep)" >&2
        status=fail
    else
        local pids
        pids=$(collect_pids "$pid")
        perf stat -x, -p "$pids" -e "$PERF_EVENTS" -- sleep "$WINDOW" >"$perfout" 2>&1 || status=fail
    fi
    wait "$pid" || status=fail
    t1=$(date +%s.%N)
    wall=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.3f", b-a}')
    read -r rs1 rsec1 ws1 wsec1 <<<"$(devstat)"
    la1=$(loadavg)
    [[ -s $json ]] || status=fail

    local ops iops p99 bytes summary
    summary=$(python3 "$MATRIX_LIB_DIR/json_summary.py" "$engine" "$json" 2>/dev/null || echo '-1 -1 -1 -1')
    read -r ops iops p99 bytes <<<"$summary"
    append_meta "$case_id" "$engine" "$variant" 4k "$qd" "$rep" "$(now_utc)" \
        "$la0" "$la1" "$((rsec1 - rsec0))" "$((wsec1 - wsec0))" \
        "$ops" "$iops" "$p99" "$wall" "$status" "$bytes"

    if [[ $status == ok && -s $perfout ]]; then
        local psum
        psum=$(python3 "$MATRIX_LIB_DIR/perf_summary.py" "$perfout" "$json" "$WINDOW" 2>/dev/null || true)
        if [[ -n $psum ]]; then
            printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
                "$case_id" "$engine" "$variant" "$qd" "$rep" "$attach_s" "$WINDOW" "$psum" >>"$PERF_TSV"
        else
            echo "warn: could not parse perf output (engine=$engine qd=$qd rep=$rep)" >&2
            status=fail
        fi
    else
        status=fail
    fi
    printf 'perf %-62s iops=%-10s wall=%-6ss %s\n' "$id" "$iops" "$wall" "$status"
    [[ $status == ok ]]
}

preflight
init_meta
init_perf_tsv
echo "perf cells: warmup=${WARMUP}s measured=${DURATION}s window=${WINDOW}s reps=${REPS} qds='${QDS}'"
echo "events='${PERF_EVENTS}' engines='${ENGINES}' fio_variants='${FIO_PERF_VARIANTS}' cpu=${MATRIX_CPU} started=$(now_utc)"
for rep in $(seq 1 "$REPS"); do
    for qd in $QDS; do
        for engine in $ENGINES; do
            if [[ $engine == fio-* ]]; then
                for fio_var in $FIO_PERF_VARIANTS; do
                    run_perf_cell "$engine" "$qd" "$rep" "$fio_var" ||
                        echo "warn: perf cell failed and was recorded (engine=$engine $fio_var qd=$qd rep=$rep)" >&2
                done
            else
                run_perf_cell "$engine" "$qd" "$rep" ||
                    echo "warn: perf cell failed and was recorded (engine=$engine qd=$qd rep=$rep)" >&2
            fi
        done
    done
done
echo "perf cells done: $(now_utc)"
