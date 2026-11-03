#!/usr/bin/env bash
# run_publication_benchmarks.sh: Executes Phase 3 publication benchmarks and records raw JSON to results/<date>/
set -euo pipefail
cd "$(dirname "$0")/.."

FROM_STEP=1
DATE="$(date +%F)"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --from)
            FROM_STEP="$2"
            shift 2
            ;;
        --date)
            DATE="$2"
            shift 2
            ;;
        *)
            if [[ "$1" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
                DATE="$1"
                shift
            else
                echo "Unknown option: $1" >&2
                exit 1
            fi
            ;;
    esac
done

RESULTS_DIR="results/$DATE"
mkdir -p "$RESULTS_DIR"

echo "================================================================================"
echo "          Starting JUring Phase 3 Publication Benchmarks Campaign               "
echo "================================================================================"
echo " Results directory: $RESULTS_DIR"
echo " Starting from step: $FROM_STEP"
echo " Target kernel:     $(uname -r)"
echo " OpenJDK:           $(java -version 2>&1 | head -n 1)"
echo " Local time:        $(date)"
echo "--------------------------------------------------------------------------------"

# 1. Record environment metadata
./bench/record_env.sh "$RESULTS_DIR/env.json" "${JURING_DATA_DIR:-/home/david/testData}"

WRITE_FILE="${JURING_DATA_DIR:-/home/david/testData}/write_files/single_file_bench.bin"
READ_FILE="${JURING_DATA_DIR:-/home/david/testData}/read_files/single_file_bench.bin"

cooldown() {
    local sec="${1:-10}"
    echo "[Cooldown] Sleeping ${sec}s for NVMe thermal recovery..."
    sleep "$sec"
}

# ==============================================================================
# PHASE A: ALL READ SUITES (Runs before any write benchmark)
# ==============================================================================

# 1. Head-to-Head fio comparison: randread (sweep best batch, then 5 repeats)
if [ "$FROM_STEP" -le 1 ]; then
    echo -e "\n>>> [1/15] Running Head-to-Head fio comparison: randread <<<"
    ./bench.sh --fio-sweep --mode read --qd 64 --runtime 5 --file "$READ_FILE" --results-dir "$RESULTS_DIR"
    ./bench.sh --fio-compare 5 --mode read --qd 64 --runtime 30 --warmup 5 --file "$READ_FILE" --results-dir "$RESULTS_DIR"
    cooldown 5
fi

# 2. NopBenchmark (Framework & Panama downcall overhead)
if [ "$FROM_STEP" -le 2 ]; then
    echo -e "\n>>> [2/15] Running NopBenchmark (Throughput, Syscalls, PerfNorm) <<<"
    ./bench.sh --include 'NopBenchmark' --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/nop.json"
    ./bench.sh --include 'NopBenchmark' --syscalls --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/nop-syscalls.json"
    ./bench.sh --include 'NopBenchmark' --perfnorm --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/nop-perfnorm.json"
    cooldown 5
fi

# 3. DirectReadBenchmark (SINGLE_FILE: QD=1, 16, 64, 128 vs FileChannel QD=1)
if [ "$FROM_STEP" -le 3 ]; then
    echo -e "\n>>> [3/15] Running DirectReadBenchmark (Throughput, Syscalls, PerfNorm) <<<"
    ./bench.sh --include 'DirectReadBenchmark' --param distribution=SINGLE_FILE --f 5 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/direct-read-single.json"
    ./bench.sh --include 'DirectReadBenchmark' --param distribution=SINGLE_FILE --syscalls --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/direct-read-single-syscalls.json"
    ./bench.sh --include 'DirectReadBenchmark' --param distribution=SINGLE_FILE --perfnorm --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/direct-read-single-perfnorm.json"
    cooldown 5
fi

# 4. MultiThreadScalingBenchmark reads (Single File: 1, 2, 4, 8, 16 Threads)
if [ "$FROM_STEP" -le 4 ]; then
    echo -e "\n>>> [4/15] Running MultiThreadScalingBenchmark reads (Single File) <<<"
    ./bench.sh --threads-scaling --include '.*DirectRead$' --param distribution=SINGLE_FILE --f 5 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/scaling-single.json"
    cooldown 10
fi

# 5. MultiThreadScalingBenchmark reads (Multi File: 2,211 discrete files)
if [ "$FROM_STEP" -le 5 ]; then
    echo -e "\n>>> [5/15] Running MultiThreadScalingBenchmark reads (Multi File) <<<"
    ./bench.sh --threads-scaling --include '.*DirectRead$' --param distribution=MULTI_FILE --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/scaling-multifile.json"
    cooldown 10
fi

# 6. VirtualThreadBenchmark (Single File & Multi File DIRECT sweeps + BUFFERED_COLD + maxPoolSize=1024)
if [ "$FROM_STEP" -le 6 ]; then
    echo -e "\n>>> [6/15] Running VirtualThreadBenchmark (DIRECT & BUFFERED_COLD) <<<"
    ./bench.sh --virtual --param distribution=SINGLE_FILE --param ioMode=DIRECT --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/virtual-threads-single.json"
    ./bench.sh --virtual --param distribution=MULTI_FILE --param ioMode=DIRECT --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/virtual-threads-multi.json"
    ./bench.sh --virtual --param distribution=SINGLE_FILE --param ioMode=BUFFERED_COLD --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/virtual-threads-buffered-cold.json"
    ./bench.sh --virtual --param distribution=SINGLE_FILE --param ioMode=DIRECT --param virtualThreads=1000 --max-pool-size --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/virtual-threads-maxpool1024.json"
    ./bench.sh --virtual --param distribution=SINGLE_FILE --param ioMode=DIRECT --syscalls --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/virtual-threads-syscalls.json"
    ./bench.sh --virtual --param distribution=SINGLE_FILE --param ioMode=DIRECT --perfnorm --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/virtual-threads-perfnorm.json"
    cooldown 5
fi

# 7. BufferedReadBenchmark (Page-Cache Resident Reads)
if [ "$FROM_STEP" -le 7 ]; then
    echo -e "\n>>> [7/15] Running BufferedReadBenchmark <<<"
    ./bench.sh --include 'BufferedReadBenchmark' --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/buffered-read.json"
    cooldown 5
fi

# 8. DirectLatencyBenchmark (QD=1 Unbatched Flash Cell Latency Percentiles)
if [ "$FROM_STEP" -le 8 ]; then
    echo -e "\n>>> [8/15] Running DirectLatencyBenchmark (QD=1 SampleTime) <<<"
    ./bench.sh --latency --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/latency.json"
    cooldown 5
fi

# 9. FfmInvocationBenchmark (Panama Foreign Downcall Invocations)
if [ "$FROM_STEP" -le 9 ]; then
    echo -e "\n>>> [9/15] Running FfmInvocationBenchmark (FFM vs Critical vs Proxies) <<<"
    ./bench.sh --include 'FfmInvocationBenchmark' --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/ffm.json"
    cooldown 5
fi

# ==============================================================================
# PHASE B: WRITE SUITES (Preconditioned, separate JMH invocations, randomised order)
# ==============================================================================

# Helper for preconditioning and logging drive state
precondition_write() {
    local target="${1:-$WRITE_FILE}"
    local min="${2:-5}"
    echo "[Preconditioning] Running fio randwrite 4KB QD=32 for ${min}m on $target..."
    ./bench/precondition.sh "$target" "$min" "$RESULTS_DIR"
}

# 10. Head-to-Head fio comparison: randwrite (QD=64, 4KB, preconditioned)
if [ "$FROM_STEP" -le 10 ]; then
    echo -e "\n>>> [10/15] Running Head-to-Head fio comparison: randwrite <<<"
    ./bench.sh --log-drive-state "$RESULTS_DIR/drive-state-fio-write-before.json"
    precondition_write "$WRITE_FILE" 5
    ./bench.sh --fio-sweep --mode write --qd 64 --runtime 5 --file "$WRITE_FILE" --results-dir "$RESULTS_DIR"
    ./bench.sh --fio-compare 5 --mode write --qd 64 --runtime 30 --warmup 5 --file "$WRITE_FILE" --results-dir "$RESULTS_DIR"
    ./bench.sh --log-drive-state "$RESULTS_DIR/drive-state-fio-write-after.json"
    cooldown 10
fi

# 11. DirectWriteBenchmark (SINGLE_FILE: separate invocations, randomised order)
if [ "$FROM_STEP" -le 11 ]; then
    echo -e "\n>>> [11/15] Running DirectWriteBenchmark (Separate Invocations) <<<"
    ./bench.sh --log-drive-state "$RESULTS_DIR/drive-state-direct-write-before.json"

    # Randomise execution order between FileChannel and JUring
    ORDER=( "fileChannelDirectWrite" "juringDirectWrite" )
    if (( RANDOM % 2 == 0 )); then
        ORDER=( "juringDirectWrite" "fileChannelDirectWrite" )
    fi

    for method in "${ORDER[@]}"; do
        precondition_write "$WRITE_FILE" 3
        ./bench.sh --include "DirectWriteBenchmark\\.${method}$" --param distribution=SINGLE_FILE \
            --f 3 --wi 2 --i 5 --w 5 --r 10 --rff "$RESULTS_DIR/direct-write-${method}.json"
        cooldown 10
    done

    # Merge results into direct-write-single.json
    jq -s 'add' "$RESULTS_DIR/direct-write-fileChannelDirectWrite.json" "$RESULTS_DIR/direct-write-juringDirectWrite.json" \
        > "$RESULTS_DIR/direct-write-single.json"

    # Syscalls and perfnorm passes
    ./bench.sh --include 'DirectWriteBenchmark' --param distribution=SINGLE_FILE --syscalls --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/direct-write-single-syscalls.json"
    ./bench.sh --include 'DirectWriteBenchmark' --param distribution=SINGLE_FILE --perfnorm --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/direct-write-single-perfnorm.json"
    ./bench.sh --log-drive-state "$RESULTS_DIR/drive-state-direct-write-after.json"
    cooldown 10
fi

# 12. MultiThreadScalingBenchmark writes (Single File: 1, 2, 4, 8, 16 Threads)
if [ "$FROM_STEP" -le 12 ]; then
    echo -e "\n>>> [12/15] Running MultiThreadScalingBenchmark writes (Single File) <<<"
    ./bench.sh --log-drive-state "$RESULTS_DIR/drive-state-scaling-write-single-before.json"
    precondition_write "$WRITE_FILE" 5
    ./bench.sh --threads-scaling --include '.*DirectWrite$' --param distribution=SINGLE_FILE \
        --f 3 --wi 2 --i 5 --w 5 --r 10 --rff "$RESULTS_DIR/scaling-single-write.json"

    # Merge read and write scaling files for each tier
    for t in 1 2 4 8 16; do
        if [[ -f "$RESULTS_DIR/scaling-single-t${t}.json" && -f "$RESULTS_DIR/scaling-single-write-t${t}.json" ]]; then
            jq -s 'add' "$RESULTS_DIR/scaling-single-t${t}.json" "$RESULTS_DIR/scaling-single-write-t${t}.json" \
                > "$RESULTS_DIR/scaling-single-t${t}.merged.json" && mv -f "$RESULTS_DIR/scaling-single-t${t}.merged.json" "$RESULTS_DIR/scaling-single-t${t}.json"
        elif [[ -f "$RESULTS_DIR/scaling-single-write-t${t}.json" ]]; then
            cp -f "$RESULTS_DIR/scaling-single-write-t${t}.json" "$RESULTS_DIR/scaling-single-t${t}.json"
        fi
    done
    ./bench.sh --log-drive-state "$RESULTS_DIR/drive-state-scaling-write-single-after.json"
    cooldown 10
fi

# 13. MultiThreadScalingBenchmark writes (Multi File: 2,211 discrete files)
if [ "$FROM_STEP" -le 13 ]; then
    echo -e "\n>>> [13/15] Running MultiThreadScalingBenchmark writes (Multi File) <<<"
    ./bench.sh --log-drive-state "$RESULTS_DIR/drive-state-scaling-write-multi-before.json"
    ./bench.sh --threads-scaling --include '.*DirectWrite$' --param distribution=MULTI_FILE \
        --f 3 --wi 2 --i 5 --w 5 --r 10 --rff "$RESULTS_DIR/scaling-multifile-write.json"

    for t in 1 2 4 8 16; do
        if [[ -f "$RESULTS_DIR/scaling-multifile-t${t}.json" && -f "$RESULTS_DIR/scaling-multifile-write-t${t}.json" ]]; then
            jq -s 'add' "$RESULTS_DIR/scaling-multifile-t${t}.json" "$RESULTS_DIR/scaling-multifile-write-t${t}.json" \
                > "$RESULTS_DIR/scaling-multifile-t${t}.merged.json" && mv -f "$RESULTS_DIR/scaling-multifile-t${t}.merged.json" "$RESULTS_DIR/scaling-multifile-t${t}.json"
        elif [[ -f "$RESULTS_DIR/scaling-multifile-write-t${t}.json" ]]; then
            cp -f "$RESULTS_DIR/scaling-multifile-write-t${t}.json" "$RESULTS_DIR/scaling-multifile-t${t}.json"
        fi
    done
    ./bench.sh --log-drive-state "$RESULTS_DIR/drive-state-scaling-write-multi-after.json"
    cooldown 10
fi

# 14. BufferedWriteBenchmark with iou-wrk sampling
if [ "$FROM_STEP" -le 14 ]; then
    echo -e "\n>>> [14/15] Running BufferedWriteBenchmark with iou-wrk worker sampling <<<"
    ./bench.sh --log-drive-state "$RESULTS_DIR/drive-state-buffered-write-before.json"

    # Start buffered write in background and sample iou-wrk threads
    ./bench.sh --include 'BufferedWriteBenchmark' --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/buffered-write.json" &
    BENCH_PID=$!
    ./bench/count_iou_wrk.sh "$BENCH_PID" "$RESULTS_DIR/iou-wrk-buffered-write.json" || true
    wait "$BENCH_PID"

    # Merge buffered read and write results
    if [[ -f "$RESULTS_DIR/buffered-read.json" && -f "$RESULTS_DIR/buffered-write.json" ]]; then
        jq -s 'add' "$RESULTS_DIR/buffered-read.json" "$RESULTS_DIR/buffered-write.json" > "$RESULTS_DIR/buffered.json"
    elif [[ -f "$RESULTS_DIR/buffered-write.json" ]]; then
        cp -f "$RESULTS_DIR/buffered-write.json" "$RESULTS_DIR/buffered.json"
    fi

    ./bench.sh --buffered --syscalls --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/buffered-syscalls.json"
    ./bench.sh --buffered --perfnorm --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/buffered-perfnorm.json"
    ./bench.sh --log-drive-state "$RESULTS_DIR/drive-state-buffered-write-after.json"
    cooldown 5
fi

# 15. Regenerate README.md tables and verify
if [ "$FROM_STEP" -le 15 ]; then
    echo -e "\n>>> [15/15] Regenerating README tables from $RESULTS_DIR <<<"
    python3 bench/generate_readme_tables.py --write README.md "$RESULTS_DIR"
    python3 bench/generate_readme_tables.py --check README.md "$RESULTS_DIR"
fi

echo "================================================================================"
echo " Publication Benchmark Run Complete! Raw JSON files written to: $RESULTS_DIR"
echo "================================================================================"
ls -lh "$RESULTS_DIR"
