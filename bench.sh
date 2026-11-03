#!/usr/bin/env bash
# Runs the JMH benchmark suite (bench.suite.BenchmarkRunner) with the test classpath.
#
# JMH_PERF is pointed at the genuine perf binary so that `--perfnorm` counts kernel + user time.
# Optional leading `--pin <cpu-list>` pins the entire JVM and its forked JMH children via `taskset -c`.
#
# Usage:
#   ./bench.sh --pin 4 --include 'DirectReadBenchmark.juringDirectRead' --param qd=64 --decision --gc
#   ./bench.sh --pin 0-7 --threads-scaling --include 'MultiThreadScalingBenchmark.juringDirectRead'
#   ./bench.sh --fio
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JMH_PERF:-}" && -x /usr/bin/perf ]]; then
    export JMH_PERF=/usr/bin/perf
fi

# Raise soft file descriptor limit to hard limit (matches HotSpot -XX:+MaxFDLimit, prevents EMFILE in multi-file runs)
if [[ $(ulimit -Sn) -lt $(ulimit -Hn) ]]; then
    ulimit -n "$(ulimit -Hn)" 2>/dev/null || ulimit -n 65536 2>/dev/null || true
fi

PIN_CPUS=""
FIO_COMPARE=false
REPEATS=3
MODE="read"
RUNTIME=3
WARMUP=1
QD=64
TARGET_FILE=""
ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pin)
            PIN_CPUS="$2"
            shift 2
            ;;
        --precondition)
            MINUTES="$2"
            FILE="${3:-}"
            shift 2
            if [[ -n "$FILE" && "$FILE" != --* ]]; then shift; fi
            if [[ -z "$FILE" ]]; then
                FILE="${TARGET_FILE:-/home/david/testData/write_files/single_file_bench.bin}"
            fi
            exec ./bench/precondition.sh "$FILE" "$MINUTES" "${RESULTS_DIR:-results/$(date +%F)}"
            ;;
        --count-iou-wrk)
            TARGET_PID="$2"
            OUT_FILE="${3:-}"
            shift 2
            if [[ -n "$OUT_FILE" && "$OUT_FILE" != --* ]]; then shift; fi
            exec ./bench/count_iou_wrk.sh "$TARGET_PID" "$OUT_FILE"
            ;;
        --log-drive-state)
            OUT_FILE="$2"
            shift 2
            exec ./bench/log_drive_state.sh "$OUT_FILE" "${DATA_DIR:-${JURING_DATA_DIR:-/home/david/testData}}"
            ;;
        --fio-sweep)
            FIO_SWEEP=true
            shift
            ;;
        --fio-compare)
            FIO_COMPARE=true
            shift
            if [[ $# -gt 0 && "$1" =~ ^[0-9]+$ ]]; then
                REPEATS="$1"
                shift
            fi
            ;;
        --repeats)
            REPEATS="$2"
            shift 2
            ;;
        --mode)
            MODE="$2"
            shift 2
            ;;
        --runtime)
            RUNTIME="$2"
            shift 2
            ;;
        --warmup)
            WARMUP="$2"
            shift 2
            ;;
        --qd)
            QD="$2"
            shift 2
            ;;
        --file)
            TARGET_FILE="$2"
            shift 2
            ;;
        --data-dir)
            DATA_DIR="$2"
            export JURING_DATA_DIR="$DATA_DIR"
            ARGS+=("-Djuring.data.dir=$DATA_DIR")
            shift 2
            ;;
        --results-dir)
            RESULTS_DIR="$2"
            export RESULTS_DIR
            export JURING_RESULTS_DIR="$RESULTS_DIR"
            ARGS+=("--results-dir" "$RESULTS_DIR")
            shift 2
            ;;
        --quick)
            RUNTIME=2
            WARMUP=1
            REPEATS=2
            ARGS+=("$1")
            shift
            ;;
        *)
            ARGS+=("$1")
            shift
            ;;
    esac
done

if [[ ! -f target/cp.txt || pom.xml -nt target/cp.txt ]]; then
    mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
else
    mvn -q test-compile
fi

TOTAL_CPUS=$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)

if [[ "${FIO_SWEEP:-false}" == "true" || "$FIO_COMPARE" == "true" ]]; then
    if ! command -v jq &>/dev/null; then
        echo "Error: jq is required for fio benchmarking but was not found in PATH." >&2
        exit 1
    fi
    FIO_BIN="${FIO_BIN:-fio}"
    if ! command -v "$FIO_BIN" &>/dev/null; then
        echo "Error: fio ($FIO_BIN) is required for fio benchmarking but was not found in PATH." >&2
        exit 1
    fi

    if [[ -z "$TARGET_FILE" ]]; then
        BASE_DATA="${JURING_DATA_DIR:-./bench-data}"
        if [[ "$MODE" == "write" ]]; then
            if [[ -f "$BASE_DATA/write_files/single_file_bench.bin" ]]; then
                TARGET_FILE="$BASE_DATA/write_files/single_file_bench.bin"
            elif [[ -f "/home/david/testData/write_files/single_file_bench.bin" ]]; then
                TARGET_FILE="/home/david/testData/write_files/single_file_bench.bin"
            elif [[ -f "/mnt/TestData/write_files/single_file_bench.bin" ]]; then
                TARGET_FILE="/mnt/TestData/write_files/single_file_bench.bin"
            elif [[ -f "$BASE_DATA/single_file_bench.bin" ]]; then
                TARGET_FILE="$BASE_DATA/single_file_bench.bin"
            else
                TARGET_FILE="$BASE_DATA/write_files/single_file_bench.bin"
            fi
        else
            if [[ -f "$BASE_DATA/read_files/single_file_bench.bin" ]]; then
                TARGET_FILE="$BASE_DATA/read_files/single_file_bench.bin"
            elif [[ -f "/home/david/testData/read_files/single_file_bench.bin" ]]; then
                TARGET_FILE="/home/david/testData/read_files/single_file_bench.bin"
            elif [[ -f "/mnt/TestData/read_files/single_file_bench.bin" ]]; then
                TARGET_FILE="/mnt/TestData/read_files/single_file_bench.bin"
            elif [[ -f "$BASE_DATA/single_file_bench.bin" ]]; then
                TARGET_FILE="$BASE_DATA/single_file_bench.bin"
            else
                TARGET_FILE="$BASE_DATA/read_files/single_file_bench.bin"
            fi
        fi
    fi

    RESULTS_DIR="${RESULTS_DIR:-results/$(date +%Y-%m-%d_%H%M%S)}"
    mkdir -p "$RESULTS_DIR"

    BENCH_METHOD="juringDirectRead"
    if [[ "$MODE" == "write" ]]; then
        BENCH_METHOD="juringDirectWrite"
    fi

    run_fio_cmd() {
        local out_file="$1"
        shift
        local fio_cmd=("$FIO_BIN"
            --name=fio_run
            --ioengine=io_uring
            --direct=1
            --bs=4k
            --size=10G
            --filename="$TARGET_FILE"
            --rw="rand${MODE}"
            --iodepth="$QD"
            --fixedbufs=1
            --registerfiles=1
            --time_based
            --runtime="${RUNTIME}s"
            --ramp_time="${WARMUP}s"
            --lat_percentiles=1
            --norandommap
            --randrepeat=0
            --output-format=json
            --output="$out_file"
            "$@")

        local status=0
        if [[ -n "$PIN_CPUS" ]]; then
            taskset -c "$PIN_CPUS" "${fio_cmd[@]}" || status=$?
        else
            "${fio_cmd[@]}" || status=$?
        fi

        if [[ $status -ne 0 ]]; then
            echo "Error: fio exited with non-zero exit code ($status)" >&2
            exit $status
        fi

        if [[ ! -f "$out_file" ]]; then
            echo "Error: fio output file $out_file was not created" >&2
            exit 1
        fi

        local fio_err
        fio_err=$(jq -r '.jobs[0].error // 1' "$out_file" 2>/dev/null || echo 1)
        if [[ "$fio_err" != "0" ]]; then
            echo "Error: fio reported error code $fio_err in $out_file" >&2
            exit 1
        fi

        local fio_iops
        if [[ "$MODE" == "write" ]]; then
            fio_iops=$(jq -r '.jobs[0].write.iops // 0' "$out_file" 2>/dev/null || echo 0)
        else
            fio_iops=$(jq -r '.jobs[0].read.iops // 0' "$out_file" 2>/dev/null || echo 0)
        fi
        if awk "BEGIN {exit !($fio_iops <= 0)}"; then
            echo "Error: fio reported zero IOPS ($fio_iops) in $out_file" >&2
            exit 1
        fi
    }

    run_sweep() {
        echo "=================================================================================="
        echo "                Running fio Batching Sweep (Mode: rand$MODE, QD: $QD)             "
        echo "=================================================================================="
        local best_iops=0
        local best_b=1
        local sweep_records=()
        for B in 1 4 8 16 32; do
            local tmp_out="$RESULTS_DIR/fio-sweep-${MODE}-B${B}.json"
            local perf_out="$RESULTS_DIR/fio-sweep-${MODE}-B${B}.perf"
            local fio_args=(
                --name=fio_sweep
                --ioengine=io_uring
                --direct=1
                --bs=4k
                --size=10G
                --filename="$TARGET_FILE"
                --rw="rand${MODE}"
                --iodepth="$QD"
                --fixedbufs=1
                --registerfiles=1
                --time_based
                --runtime="${RUNTIME}s"
                --ramp_time="${WARMUP}s"
                --norandommap
                --randrepeat=0
                --iodepth_batch_submit="$B"
                --iodepth_batch_complete_min=1
                --iodepth_batch_complete_max="$QD"
                --output-format=json
                --output="$tmp_out"
            )
            local status=0
            if perf list 'syscalls:sys_enter_io_uring_enter' &>/dev/null; then
                perf stat -e syscalls:sys_enter_io_uring_enter -x, -o "$perf_out" -- "$FIO_BIN" "${fio_args[@]}" || status=$?
            else
                "$FIO_BIN" "${fio_args[@]}" || status=$?
            fi
            if [[ $status -ne 0 ]]; then
                echo "Error: fio exited with non-zero code ($status) during sweep B=$B" >&2
                exit $status
            fi
            local cur_iops=0
            local total_ios=0
            if [[ "$MODE" == "write" ]]; then
                cur_iops=$(jq -r '.jobs[0].write.iops // 0' "$tmp_out" 2>/dev/null || echo 0)
                total_ios=$(jq -r '.jobs[0].write.total_ios // 0' "$tmp_out" 2>/dev/null || echo 0)
            else
                cur_iops=$(jq -r '.jobs[0].read.iops // 0' "$tmp_out" 2>/dev/null || echo 0)
                total_ios=$(jq -r '.jobs[0].read.total_ios // 0' "$tmp_out" 2>/dev/null || echo 0)
            fi
            if awk "BEGIN {exit !($cur_iops <= 0)}"; then
                echo "Error: fio reported zero IOPS ($cur_iops) in sweep B=$B" >&2
                exit 1
            fi
            local enters_per_op=0
            if [[ -f "$perf_out" && "$total_ios" -gt 0 ]]; then
                local enters=$(grep 'syscalls:sys_enter_io_uring_enter' "$perf_out" | cut -d',' -f1 || echo 0)
                if [[ "$enters" =~ ^[0-9]+$ && "$enters" -gt 0 ]]; then
                    enters_per_op=$(awk -v e="$enters" -v i="$total_ios" 'BEGIN {printf "%.4f", e / i}')
                fi
            fi
            echo " [fio-sweep] B=$B -> IOPS: $cur_iops, enters/op: $enters_per_op"
            sweep_records+=("{\"batch\": $B, \"iops\": $cur_iops, \"enters_per_op\": $enters_per_op}")
            if awk "BEGIN {exit !($cur_iops > $best_iops)}"; then
                best_iops="$cur_iops"
                best_b="$B"
            fi
        done
        echo "Selected best fio batch: B=$best_b (IOPS: $best_iops)"
        echo "$best_b" > "$RESULTS_DIR/fio-best-${MODE}.txt"
        printf '%s\n' "${sweep_records[@]}" | jq -s . > "$RESULTS_DIR/fio-sweep-${MODE}.json"
        BEST_B="$best_b"
    }

    if [[ "${FIO_SWEEP:-false}" == "true" ]]; then
        run_sweep
        exit 0
    fi

    # --fio-compare
    BEST_B=32
    if [[ -f "$RESULTS_DIR/fio-best-${MODE}.txt" ]]; then
        BEST_B=$(cat "$RESULTS_DIR/fio-best-${MODE}.txt" | tr -d '[:space:]')
    else
        echo "No existing fio-best-${MODE}.txt found. Running sweep to find optimal batching..."
        run_sweep
    fi

    cat <<EOF > "$RESULTS_DIR/meta.json"
{
  "pinning": "$([[ -n "$PIN_CPUS" ]] && echo "pinned" || echo "unpinned")",
  "pin_cpus": $([[ -n "$PIN_CPUS" ]] && echo "\"$PIN_CPUS\"" || echo "null"),
  "active_processor_count": $TOTAL_CPUS,
  "gc": "$([[ -n "$PIN_CPUS" ]] && echo "G1GC" || echo "default")",
  "mode": "rand$MODE",
  "qd": $QD,
  "runtime": $RUNTIME,
  "warmup": $WARMUP,
  "fio_best_b": $BEST_B,
  "target_file": "$TARGET_FILE"
}
EOF

    echo "=================================================================================="
    echo "       Starting JUring vs Native C fio Head-to-Head Comparison Suite             "
    echo "=================================================================================="
    echo " Results Directory: $RESULTS_DIR"
    echo " Pinning:           $([[ -n "$PIN_CPUS" ]] && echo "Pinned ($PIN_CPUS)" || echo "Unpinned")"
    echo " Repeats (N):       $REPEATS"
    echo " Workload Mode:     rand$MODE"
    echo " Queue Depth (QD):  $QD"
    echo " Fio Best Batch (B): $BEST_B"
    echo " Target File:       $TARGET_FILE"
    echo " Warmup / Window:   ${WARMUP}s / ${RUNTIME}s"
    echo " Engines:           3 (fio default, fio best B=$BEST_B, JMH JUring)"
    echo " Execution Order:   Randomized per repeat"
    echo "----------------------------------------------------------------------------------"

    for ((rep = 1; rep <= REPEATS; rep++)); do
        JMH_OUT="$RESULTS_DIR/jmh-${MODE}-$rep.json"
        FIO_DEF_OUT="$RESULTS_DIR/fio-default-${MODE}-$rep.json"
        FIO_BEST_OUT="$RESULTS_DIR/fio-best-${MODE}-$rep.json"

        run_jmh() {
            echo " [Repeat $rep/$REPEATS] Running JMH FioComparisonBenchmark ($BENCH_METHOD)..."
            local jvm_args=(java --enable-native-access=ALL-UNNAMED -Djuring.results.dir="$RESULTS_DIR")
            local fork_jvm_args="-Djuring.results.dir=$RESULTS_DIR"
            if [[ -n "$PIN_CPUS" ]]; then
                jvm_args+=(-XX:ActiveProcessorCount="$TOTAL_CPUS" -XX:+UseG1GC -Djuring.pin="$PIN_CPUS")
                fork_jvm_args+=" -XX:ActiveProcessorCount=$TOTAL_CPUS -XX:+UseG1GC -Djuring.pin=$PIN_CPUS"
            else
                jvm_args+=(-Djuring.pin="unpinned")
                fork_jvm_args+=" -Djuring.pin=unpinned"
            fi
            local jmh_cmd=("${jvm_args[@]}"
                -cp "target/test-classes:target/classes:$(cat target/cp.txt)"
                org.openjdk.jmh.Main ".*FioComparisonBenchmark\\.${BENCH_METHOD}$"
                -f 1 -wi 1 -w "${WARMUP}s" -i 1 -r "${RUNTIME}s"
                -p qd="$QD" -p telemetry=true
                -jvmArgsAppend "$fork_jvm_args"
                -rf json -rff "$JMH_OUT")
            if [[ -n "$PIN_CPUS" ]]; then
                taskset -c "$PIN_CPUS" "${jmh_cmd[@]}"
            else
                "${jmh_cmd[@]}"
            fi
        }

        run_fio_default() {
            echo " [Repeat $rep/$REPEATS] Running Native C fio (default settings)..."
            run_fio_cmd "$FIO_DEF_OUT"
            cp -f "$FIO_DEF_OUT" "$RESULTS_DIR/fio-${MODE}-$rep.json" 2>/dev/null || true
        }

        run_fio_best() {
            echo " [Repeat $rep/$REPEATS] Running Native C fio (best batching B=$BEST_B)..."
            run_fio_cmd "$FIO_BEST_OUT" \
                --iodepth_batch_submit="$BEST_B" \
                --iodepth_batch_complete_min=1 \
                --iodepth_batch_complete_max="$QD"
        }

        ENGINES=(0 1 2)
        # Fisher-Yates shuffle
        for ((i = 2; i > 0; i--)); do
            j=$(( RANDOM % (i + 1) ))
            tmp=${ENGINES[i]}
            ENGINES[i]=${ENGINES[j]}
            ENGINES[j]=$tmp
        done

        echo "--- Repeat $rep of $REPEATS ---"
        for eng in "${ENGINES[@]}"; do
            case $eng in
                0) run_fio_default ;;
                1) run_fio_best ;;
                2) run_jmh ;;
            esac
        done
    done

    # Measure enters/op for all three engines in a separate pass
    echo "--- Measuring io_uring_enter calls per op for each engine ---"
    FIO_DEF_ENTERS_OP="unknown"
    FIO_BEST_ENTERS_OP="unknown"
    JMH_ENTERS_OP="unknown"

    if perf list 'syscalls:sys_enter_io_uring_enter' &>/dev/null; then
        # fio default
        perf_def="$RESULTS_DIR/fio-enters-def.perf"
        json_def="$RESULTS_DIR/fio-enters-def.json"
        perf stat -e syscalls:sys_enter_io_uring_enter -x, -o "$perf_def" -- "$FIO_BIN" \
            --name=fio_perf_def --ioengine=io_uring --direct=1 --bs=4k --size=10G --filename="$TARGET_FILE" \
            --rw="rand${MODE}" --iodepth="$QD" --fixedbufs=1 --registerfiles=1 --time_based --runtime=5s --ramp_time=1s \
            --norandommap --randrepeat=0 --output-format=json --output="$json_def" &>/dev/null || true
        if [[ -f "$json_def" && -f "$perf_def" ]]; then
            local ios=0
            if [[ "$MODE" == "write" ]]; then
                ios=$(jq -r '.jobs[0].write.total_ios // 0' "$json_def" 2>/dev/null || echo 0)
            else
                ios=$(jq -r '.jobs[0].read.total_ios // 0' "$json_def" 2>/dev/null || echo 0)
            fi
            ent=$(grep 'syscalls:sys_enter_io_uring_enter' "$perf_def" | cut -d',' -f1 || echo 0)
            if [[ "$ios" -gt 0 && "$ent" =~ ^[0-9]+$ ]]; then
                FIO_DEF_ENTERS_OP=$(awk -v e="$ent" -v i="$ios" 'BEGIN {printf "%.4f", e / i}')
            fi
        fi

        # fio best
        perf_best="$RESULTS_DIR/fio-enters-best.perf"
        json_best="$RESULTS_DIR/fio-enters-best.json"
        perf stat -e syscalls:sys_enter_io_uring_enter -x, -o "$perf_best" -- "$FIO_BIN" \
            --name=fio_perf_best --ioengine=io_uring --direct=1 --bs=4k --size=10G --filename="$TARGET_FILE" \
            --rw="rand${MODE}" --iodepth="$QD" --fixedbufs=1 --registerfiles=1 --time_based --runtime=5s --ramp_time=1s \
            --iodepth_batch_submit="$BEST_B" --iodepth_batch_complete_min=1 --iodepth_batch_complete_max="$QD" \
            --norandommap --randrepeat=0 --output-format=json --output="$json_best" &>/dev/null || true
        if [[ -f "$json_best" && -f "$perf_best" ]]; then
            local ios=0
            if [[ "$MODE" == "write" ]]; then
                ios=$(jq -r '.jobs[0].write.total_ios // 0' "$json_best" 2>/dev/null || echo 0)
            else
                ios=$(jq -r '.jobs[0].read.total_ios // 0' "$json_best" 2>/dev/null || echo 0)
            fi
            ent=$(grep 'syscalls:sys_enter_io_uring_enter' "$perf_best" | cut -d',' -f1 || echo 0)
            if [[ "$ios" -gt 0 && "$ent" =~ ^[0-9]+$ ]]; then
                FIO_BEST_ENTERS_OP=$(awk -v e="$ent" -v i="$ios" 'BEGIN {printf "%.4f", e / i}')
            fi
        fi

        # JMH enters/op
        jmh_sys="$RESULTS_DIR/jmh-enters-${MODE}.json"
        java --enable-native-access=ALL-UNNAMED -cp "target/test-classes:target/classes:$(cat target/cp.txt)" \
            org.openjdk.jmh.Main ".*FioComparisonBenchmark\\.${BENCH_METHOD}$" \
            -f 1 -wi 1 -w 2s -i 1 -r 5s -p qd="$QD" -p telemetry=false \
            -prof "bench.suite.profilers.SyscallProfiler:events=syscalls:sys_enter_io_uring_enter" \
            -rf json -rff "$jmh_sys" &>/dev/null || true
        if [[ -f "$jmh_sys" ]]; then
            j_ent=$(jq -r '.[0].secondaryMetrics["syscalls:sys_enter_io_uring_enter"].score // empty' "$jmh_sys" 2>/dev/null || true)
            if [[ -n "$j_ent" && "$j_ent" != "null" ]]; then
                JMH_ENTERS_OP=$(awk -v e="$j_ent" 'BEGIN {printf "%.4f", e}')
            fi
        fi
    fi

    jq -n \
        --arg mode "$MODE" \
        --arg best_b "$BEST_B" \
        --arg fio_default "$FIO_DEF_ENTERS_OP" \
        --arg fio_best "$FIO_BEST_ENTERS_OP" \
        --arg juring "$JMH_ENTERS_OP" \
        '{mode: $mode, best_batch: $best_b, fio_default_enters_op: $fio_default, fio_best_enters_op: $fio_best, juring_enters_op: $juring}' \
        > "$RESULTS_DIR/fio-enters-${MODE}.json"

    echo
    ./bench/fio-compare-table.sh "$RESULTS_DIR" "$MODE"
    exit 0
fi

LAUNCHER_JVM_FLAGS=(--enable-native-access=ALL-UNNAMED)
if [[ -n "${JURING_DATA_DIR:-}" ]]; then
    LAUNCHER_JVM_FLAGS+=("-Djuring.data.dir=$JURING_DATA_DIR")
fi
if [[ -n "${RESULTS_DIR:-}" ]]; then
    LAUNCHER_JVM_FLAGS+=("-Djuring.results.dir=$RESULTS_DIR")
fi
if [[ -n "$PIN_CPUS" ]]; then
    LAUNCHER_JVM_FLAGS+=(-XX:ActiveProcessorCount="$TOTAL_CPUS" -XX:+UseG1GC -Djuring.pin="$PIN_CPUS")
    ARGS+=(--pin "$PIN_CPUS")
else
    LAUNCHER_JVM_FLAGS+=(-Djuring.pin="unpinned")
fi

CMD=(java "${LAUNCHER_JVM_FLAGS[@]}"
     -cp "target/test-classes:target/classes:$(cat target/cp.txt)"
     bench.suite.BenchmarkRunner "${ARGS[@]}")

if [[ -n "$PIN_CPUS" ]]; then
    echo "Pinning benchmark JVM to CPUs: $PIN_CPUS (-XX:ActiveProcessorCount=$TOTAL_CPUS, -XX:+UseG1GC)"
    exec taskset -c "$PIN_CPUS" "${CMD[@]}"
else
    exec "${CMD[@]}"
fi
