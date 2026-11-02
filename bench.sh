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
        --quick)
            RUNTIME=2
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

if [[ ! -f target/cp.txt ]]; then
    mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
else
    mvn -q test-compile
fi

if [[ "$FIO_COMPARE" == "true" ]]; then
    if ! command -v jq &>/dev/null; then
        echo "Error: jq is required for --fio-compare but was not found in PATH." >&2
        exit 1
    fi
    FIO_BIN="${FIO_BIN:-fio}"
    if ! command -v "$FIO_BIN" &>/dev/null; then
        echo "Error: fio ($FIO_BIN) is required for --fio-compare but was not found in PATH." >&2
        exit 1
    fi

    if [[ -z "$TARGET_FILE" ]]; then
        if [[ -f "/mnt/TestData/write_files/single_file_bench.bin" ]]; then
            TARGET_FILE="/mnt/TestData/write_files/single_file_bench.bin"
        elif [[ -f "data/single_file_bench.bin" ]]; then
            TARGET_FILE="data/single_file_bench.bin"
        else
            TARGET_FILE="/mnt/TestData/write_files/single_file_bench.bin"
        fi
    fi

    DATE=$(date +%Y-%m-%d_%H%M%S)
    RESULTS_DIR="results/$DATE"
    mkdir -p "$RESULTS_DIR"

    BENCH_METHOD="juringDirectRead"
    if [[ "$MODE" == "write" ]]; then
        BENCH_METHOD="juringDirectWrite"
    fi

    echo "=================================================================================="
    echo "       Starting JUring vs Native C fio Head-to-Head Comparison Suite             "
    echo "=================================================================================="
    echo " Results Directory: $RESULTS_DIR"
    echo " Repeats (N):       $REPEATS"
    echo " Workload Mode:     rand$MODE"
    echo " Queue Depth (QD):  $QD"
    echo " Target File:       $TARGET_FILE"
    echo " Measurement Window: ${RUNTIME}s"
    echo " Random Execution:  Alternate order per repeat"
    echo "----------------------------------------------------------------------------------"

    for ((rep = 1; rep <= REPEATS; rep++)); do
        JMH_OUT="$RESULTS_DIR/jmh-$rep.json"
        FIO_OUT="$RESULTS_DIR/fio-${MODE}-$rep.json"

        run_jmh() {
            echo " [Repeat $rep/$REPEATS] Running JMH FioComparisonBenchmark ($BENCH_METHOD)..."
            local jmh_cmd=(java --enable-native-access=ALL-UNNAMED
                -cp "target/test-classes:target/classes:$(cat target/cp.txt)"
                org.openjdk.jmh.Main ".*FioComparisonBenchmark\\.${BENCH_METHOD}$"
                -f 1 -wi 1 -w "${WARMUP}s" -i 1 -r "${RUNTIME}s"
                -p qd="$QD" -p telemetry=true
                -rf json -rff "$JMH_OUT")
            if [[ -n "$PIN_CPUS" ]]; then
                taskset -c "$PIN_CPUS" "${jmh_cmd[@]}"
            else
                "${jmh_cmd[@]}"
            fi
        }

        run_fio() {
            echo " [Repeat $rep/$REPEATS] Running Native C fio (ioengine=io_uring)..."
            local fio_cmd=("$FIO_BIN"
                --name=fio_compare
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
                --lat_percentiles=1
                --norandommap
                --randrepeat=0
                --output-format=json
                --output="$FIO_OUT")
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

            if [[ ! -f "$FIO_OUT" ]]; then
                echo "Error: fio output file $FIO_OUT was not created" >&2
                exit 1
            fi

            local fio_err
            fio_err=$(jq -r '.jobs[0].error // 1' "$FIO_OUT")
            if [[ "$fio_err" != "0" ]]; then
                echo "Error: fio reported error code $fio_err in $FIO_OUT" >&2
                exit 1
            fi

            local fio_iops
            fio_iops=$(jq -r '.jobs[0].read.iops // .jobs[0].write.iops // 0' "$FIO_OUT")
            if awk "BEGIN {exit !($fio_iops <= 0)}"; then
                echo "Error: fio reported zero IOPS ($fio_iops) in $FIO_OUT" >&2
                exit 1
            fi
        }

        COIN=$(( RANDOM % 2 ))
        if [[ $COIN -eq 0 ]]; then
            echo "--- Repeat $rep of $REPEATS (Order: JMH -> fio) ---"
            run_jmh
            run_fio
        else
            echo "--- Repeat $rep of $REPEATS (Order: fio -> JMH) ---"
            run_fio
            run_jmh
        fi
    done

    echo
    ./bench/fio-compare-table.sh "$RESULTS_DIR"
    exit 0
fi

CMD=(java --enable-native-access=ALL-UNNAMED
     -cp "target/test-classes:target/classes:$(cat target/cp.txt)"
     bench.suite.BenchmarkRunner "${ARGS[@]}")

if [[ -n "$PIN_CPUS" ]]; then
    echo "Pinning benchmark JVM to CPUs: $PIN_CPUS"
    exec taskset -c "$PIN_CPUS" "${CMD[@]}"
else
    exec "${CMD[@]}"
fi
